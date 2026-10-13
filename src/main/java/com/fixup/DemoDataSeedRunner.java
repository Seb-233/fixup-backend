package com.fixup;

import com.fixup.media.api.MediaObjectWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carga los datos de demostración de Colombia (src/main/resources/seed/colombia.sql, generado por
 * scripts/seed/generar_seed_colombia.py) y sube sus fotos al almacén de objetos.
 *
 * <p>Solo corre con {@code fixup.seed.enabled=true}: el perfil demo lo activa y cualquier otro
 * despliegue lo pide de forma explícita (FIXUP_SEED_ENABLED=true). Es idempotente: si ya existen
 * usuarios {@code seed|...} no vuelve a insertar nada, y solo sube las fotos que falten.
 *
 * <p>Las cinco cuentas protagonistas se resuelven por su subject del proveedor de identidad: en
 * local son las cuentas del perfil demo y en un despliegue con Auth0 se configuran los subjects de
 * las cuentas reales de demostración, de modo que al iniciar sesión vean sus propios datos. Vive en
 * el paquete raíz, junto al resto de la composición local de demo, porque no es una regla de negocio
 * de ningún módulo.
 */
@Component
@ConditionalOnProperty(name = "fixup.seed.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
class DemoDataSeedRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeedRunner.class);
    private static final Pattern TIME = Pattern.compile("\\{\\{T:(-?\\d+)\\}\\}");
    private static final Pattern DATE = Pattern.compile("\\{\\{D:(-?\\d+)\\}\\}");
    private static final Pattern USER = Pattern.compile("\\{\\{U:(\\w+)\\}\\}");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final MediaObjectWriter objects;
    private final Clock clock;
    private final Map<String, Protagonist> protagonists;

    DemoDataSeedRunner(JdbcTemplate jdbc, DataSource dataSource, PlatformTransactionManager transactionManager,
            MediaObjectWriter objects, Clock clock,
            @Value("${fixup.seed.owner-subject:auth0|demo-owner}") String ownerSubject,
            @Value("${fixup.seed.tenant-subject:auth0|demo-tenant}") String tenantSubject,
            @Value("${fixup.seed.fixer-subject:auth0|demo-fixer}") String fixerSubject,
            @Value("${fixup.seed.manager-subject:auth0|demo-real_estate_manager}") String managerSubject,
            @Value("${fixup.seed.admin-subject:auth0|demo-platform_admin}") String adminSubject) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.objects = objects;
        this.clock = clock;
        this.protagonists = new LinkedHashMap<>();
        protagonists.put("owner", new Protagonist(ownerSubject, "Carolina Restrepo Arango",
                "carolina.restrepo@correo.fixrent.test", List.of("OWNER")));
        protagonists.put("tenant", new Protagonist(tenantSubject, "Andrés Moreno Rincón",
                "andres.moreno@correo.fixrent.test", List.of("TENANT")));
        protagonists.put("fixer", new Protagonist(fixerSubject, "Jhon Fredy Cárdenas Ospina",
                "jhon.cardenas@correo.fixrent.test", List.of("FIXER")));
        protagonists.put("manager", new Protagonist(managerSubject, "Inmobiliaria Cordillera",
                "contacto@cordillera.fixrent.test", List.of("REAL_ESTATE_MANAGER", "OWNER")));
        protagonists.put("admin", new Protagonist(adminSubject, "Soporte Fix&Rent",
                "soporte@fixrent.test", List.of("PLATFORM_ADMIN", "REAL_ESTATE_MANAGER", "OWNER")));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (alreadySeeded()) {
            log.info("Demo data already present; checking that every seed photo is in storage.");
        } else {
            var started = System.currentTimeMillis();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> load());
            log.info("Demo data loaded in {} ms.", System.currentTimeMillis() - started);
        }
        uploadPhotos();
    }

    private boolean alreadySeeded() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM users WHERE auth0_subject LIKE 'seed|%'",
                Integer.class);
        return count != null && count > 0;
    }

    private void load() {
        var ids = new LinkedHashMap<String, UUID>();
        protagonists.forEach((key, protagonist) -> ids.put(key, ensure(protagonist)));
        // The script brings the protagonist fixer's verified profile; a pending one created by an earlier
        // login is replaced so the insert does not collide with it.
        var fixer = ids.get("fixer");
        jdbc.update("DELETE FROM fixer_verification_documents WHERE user_id = ?", fixer);
        jdbc.update("DELETE FROM fixer_specialties WHERE fixer_user_id = ?", fixer);
        jdbc.update("DELETE FROM fixer_profiles WHERE user_id = ?", fixer);

        var script = resolve(read("seed/colombia.sql"), ids);
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** Finds the protagonist by subject or creates it, and makes sure it holds the demo roles. */
    private UUID ensure(Protagonist protagonist) {
        var existing = jdbc.queryForList("SELECT id FROM users WHERE auth0_subject = ?", UUID.class,
                protagonist.subject());
        UUID id;
        var now = java.sql.Timestamp.from(Instant.now(clock));
        if (existing.isEmpty()) {
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)", id, protagonist.subject(), protagonist.email(),
                    protagonist.displayName(), now, now);
        } else {
            id = existing.get(0);
            jdbc.update("UPDATE users SET display_name = ?, status = 'ACTIVE', updated_at = ? WHERE id = ?",
                    protagonist.displayName(), now, id);
        }
        for (String role : protagonist.roles()) {
            Integer present = jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ? AND role = ?",
                    Integer.class, id, role);
            if (present == null || present == 0) {
                jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
            }
        }
        return id;
    }

    private String resolve(String script, Map<String, UUID> ids) {
        var now = Instant.now(clock);
        var today = LocalDate.now(clock.withZone(COLOMBIA));
        var withTimes = replace(TIME, script, match -> "TIMESTAMP WITH TIME ZONE '"
                + TIMESTAMP.format(now.plusSeconds(Long.parseLong(match.group(1)))) + "+00:00'");
        var withDates = replace(DATE, withTimes, match -> "DATE '" + today.plusDays(Long.parseLong(match.group(1))) + "'");
        return replace(USER, withDates, match -> {
            var id = ids.get(match.group(1));
            if (id == null) {
                throw new IllegalStateException("Unknown seed protagonist: " + match.group(1));
            }
            return "'" + id + "'";
        });
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<Matcher, String> value) {
        var matcher = pattern.matcher(text);
        var result = new StringBuilder(text.length() + text.length() / 8);
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(value.apply(matcher)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Seed object keys look like {@code seed/propiedad/sala-03/<uuid>.jpg}: the middle part names the photo
     * under {@code seed/images}. Each asset gets its own key because the schema keeps keys unique.
     */
    private void uploadPhotos() {
        var keys = jdbc.queryForList("SELECT object_key FROM media_assets WHERE object_key LIKE 'seed/%'", String.class);
        var cache = new ConcurrentHashMap<String, byte[]>();
        var uploaded = new AtomicInteger();
        var failed = new AtomicInteger();
        keys.parallelStream().forEach(key -> {
            try {
                if (objects.exists(key)) {
                    return;
                }
                var source = "seed/images/" + key.substring("seed/".length(), key.lastIndexOf('/')) + ".jpg";
                objects.put(key, "image/jpeg", cache.computeIfAbsent(source, DemoDataSeedRunner::readBytes));
                uploaded.incrementAndGet();
            } catch (RuntimeException exception) {
                if (failed.incrementAndGet() <= 3) {
                    log.warn("Could not upload seed photo {}: {}", key, exception.getMessage());
                }
            }
        });
        log.info("Seed photos: {} in database, {} uploaded now, {} failed.", keys.size(), uploaded.get(), failed.get());
    }

    private static String read(String path) {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Missing seed resource " + path, exception);
        }
    }

    private record Protagonist(String subject, String displayName, String email, List<String> roles) {
    }
}

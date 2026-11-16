package com.fixup.requests.application;

import com.fixup.fixers.api.Specialty;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.requests.api.PaymentMethod;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaBreached;
import com.fixup.requests.api.SlaWarningRaised;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RNF-04 — disponibilidad del compromiso de 48 horas para solicitudes urgentes.
 *
 * <p>El requisito pide tres cosas: asignar técnico, notificar y escalar automáticamente. De las
 * tres, el sistema implementa el escalamiento; la asignación automática no existe (el único camino
 * a ASSIGNED es que el propietario acepte una cotización) y el aviso de creación llega solo al
 * propietario. Estas pruebas cubren lo que sí está implementado, y fijan dos desajustes entre la
 * métrica declarada y el comportamiento real para que queden a la vista y no se descubran en una
 * sustentación.
 *
 * <p>El reloj se inyecta, así que el ciclo completo de 48 horas se verifica en milisegundos en vez
 * de esperar dos días. Esa costura es, en sí misma, la táctica de comprobabilidad del módulo.
 *
 * <p>Complementa a {@code SlaCheckSchedulerTest}, que cubre las transiciones básicas; aquí van los
 * bordes, los destinatarios y los casos que no deben disparar nada.
 */
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class SlaEscalationRnf04Test {

    /** 48 h × 0,20 = 9 h 36 min antes del vencimiento, o sea a las 38,4 h de creada. */
    private static final Duration UMBRAL_DE_AVISO = Duration.ofMinutes(576);

    @Autowired SlaCheckScheduler scheduler;
    @MockBean RepairRequests requests;
    @Autowired ApplicationEvents events;

    private Instant ahora;
    private UUID requestId;
    private UUID propertyId;
    private UUID propietario;
    private UUID administrador;
    private UUID tecnico;

    @BeforeEach
    void prepararIdentificadores() {
        ahora = Instant.parse("2026-07-01T10:00:00Z");
        requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        propertyId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        propietario = UUID.fromString("33333333-3333-3333-3333-333333333333");
        administrador = UUID.fromString("44444444-4444-4444-4444-444444444444");
        tecnico = UUID.fromString("55555555-5555-5555-5555-555555555555");
    }

    private RepairRequest urgente(RepairRequestStatus estado, Instant plazo, UUID gestor, UUID asignado) {
        return new RepairRequest(requestId, propertyId, "Bogotá", propietario, Specialty.PLUMBING,
                "Fuga en la cocina", "El agua no para", List.of(), estado, asignado,
                PaymentMethod.TRANSFER, ahora.minus(Duration.ofHours(40)), ahora.minus(Duration.ofHours(40)),
                UrgencyLevel.URGENT, plazo, null, propietario, gestor, "Casa 1", "Calle 1 # 2-3", null);
    }

    private void seEncuentra(RepairRequest... encontradas) {
        when(requests.findUrgentUnresolved()).thenReturn(List.of(encontradas));
    }

    private List<RepairRequest> guardadas() {
        ArgumentCaptor<RepairRequest> captor = ArgumentCaptor.forClass(RepairRequest.class);
        verify(requests, atLeastOnce()).update(captor.capture());
        return captor.getAllValues();
    }

    private List<NotificationRequested> avisosDeTipo(NotificationType tipo) {
        return events.stream(NotificationRequested.class).filter(n -> n.type() == tipo).toList();
    }

    // ---------- el borde del aviso ----------

    @Test
    void elAvisoSeDisparaAlLlegarAlUmbralYNoUnMinutoAntes() {
        // Justo en el umbral: dispara.
        seEncuentra(urgente(RepairRequestStatus.OPEN, ahora.plus(UMBRAL_DE_AVISO), null, null));
        scheduler.checkSlas();

        assertThat(guardadas()).extracting(RepairRequest::status)
                .contains(RepairRequestStatus.SLA_WARNING);
        assertThat(events.stream(SlaWarningRaised.class)).hasSize(1);
    }

    @Test
    void unMinutoAntesDelUmbralTodaviaNoSeAvisa() {
        seEncuentra(urgente(RepairRequestStatus.OPEN,
                ahora.plus(UMBRAL_DE_AVISO).plus(Duration.ofMinutes(1)), null, null));

        scheduler.checkSlas();

        verify(requests, never()).update(any(RepairRequest.class));
        assertThat(events.stream(SlaWarningRaised.class)).isEmpty();
    }

    /**
     * Deja escrito el desajuste con la métrica. RNF-04 dice "escalamiento automático al cumplirse
     * 36 h sin cierre (valor propuesto, configurable)". El código avisa a las 38,4 h, porque el
     * umbral es 48 h × 0,20, y además es una constante: no hay nada configurable. Si alguien
     * cambia el umbral, esta prueba falla y obliga a decidir cuál de los dos documentos manda.
     */
    @Test
    void elUmbralRealEsDe38HorasYNoDe36ComoDiceLaMetrica() {
        Instant creada = ahora.minus(Duration.ofHours(36));
        Instant plazo = creada.plus(Duration.ofHours(48));

        seEncuentra(urgente(RepairRequestStatus.OPEN, plazo, null, null));
        scheduler.checkSlas();

        // A las 36 h todavía quedan 12 h de plazo, más que las 9,6 h del umbral: no avisa.
        verify(requests, never()).update(any(RepairRequest.class));
        assertThat(events.stream(SlaWarningRaised.class)).isEmpty();
        assertThat(Duration.between(ahora, plazo)).isGreaterThan(UMBRAL_DE_AVISO);
    }

    // ---------- a quién se le avisa ----------

    @Test
    void elEscalamientoLlegaAlPropietarioAlAdministradorYAlTecnicoAsignado() {
        seEncuentra(urgente(RepairRequestStatus.ASSIGNED, ahora.minus(Duration.ofHours(1)),
                administrador, tecnico));

        scheduler.checkSlas();

        assertThat(avisosDeTipo(NotificationType.REQUEST_SLA_BREACHED))
                .extracting(NotificationRequested::recipientUserId)
                .containsExactlyInAnyOrder(propietario, administrador, tecnico);
    }

    @Test
    void sinAdministradorNiTecnicoElAvisoLlegaSoloAlPropietario() {
        seEncuentra(urgente(RepairRequestStatus.OPEN, ahora.minus(Duration.ofMinutes(1)), null, null));

        scheduler.checkSlas();

        assertThat(avisosDeTipo(NotificationType.REQUEST_SLA_BREACHED))
                .extracting(NotificationRequested::recipientUserId)
                .containsExactly(propietario);
    }

    @Test
    void siElAdministradorEsElMismoPropietarioNoSeAvisaDosVeces() {
        seEncuentra(urgente(RepairRequestStatus.OPEN, ahora.minus(Duration.ofMinutes(1)),
                propietario, null));

        scheduler.checkSlas();

        assertThat(avisosDeTipo(NotificationType.REQUEST_SLA_BREACHED)).hasSize(1);
    }

    // ---------- lo que no debe disparar nada ----------

    @Test
    void unaSolicitudSinPlazoNuncaEscala() {
        // Solo las URGENT reciben plazo; el resto lo tiene en null y debe ignorarse.
        seEncuentra(urgente(RepairRequestStatus.OPEN, null, administrador, tecnico));

        scheduler.checkSlas();

        verify(requests, never()).update(any(RepairRequest.class));
        assertThat(events.stream(SlaWarningRaised.class)).isEmpty();
        assertThat(events.stream(SlaBreached.class)).isEmpty();
    }

    @Test
    void lasSolicitudesCerradasNoEscalanAunqueElPlazoHayaVencido() {
        Instant vencido = ahora.minus(Duration.ofHours(5));
        seEncuentra(
                urgente(RepairRequestStatus.COMPLETED, vencido, null, null),
                urgente(RepairRequestStatus.CANCELLED, vencido, null, null));

        scheduler.checkSlas();

        verify(requests, never()).update(any(RepairRequest.class));
        assertThat(events.stream(SlaBreached.class)).isEmpty();
    }

    @Test
    void unaSolicitudYaIncumplidaNoSeVuelveANotificarEnElSiguienteCiclo() {
        // El planificador corre cada 5 minutos; sin esta guarda, una solicitud vencida generaría
        // un aviso cada 5 minutos hasta que alguien la cerrara.
        seEncuentra(urgente(RepairRequestStatus.SLA_BREACHED, ahora.minus(Duration.ofHours(3)),
                administrador, tecnico));

        scheduler.checkSlas();

        verify(requests, never()).update(any(RepairRequest.class));
        assertThat(events.stream(SlaBreached.class)).isEmpty();
    }

    // ---------- el defecto ----------

    /**
     * DEFECTO CONOCIDO, documentado a propósito. {@code SLA_WARNING} está dentro del conjunto de
     * estados que el planificador considera terminales, así que una solicitud que ya recibió el
     * aviso previo jamás pasa a {@code SLA_BREACHED}, por mucho que venzan las 48 horas.
     *
     * <p>Como el aviso siempre ocurre antes del vencimiento, en la práctica el incumplimiento solo
     * se registra en solicitudes que nunca fueron avisadas. El propio repositorio contradice esa
     * guarda: {@code findUrgentUnresolved()} devuelve explícitamente las que están en
     * {@code SLA_WARNING}, es decir, las entrega para que se escalen, y el planificador las
     * descarta.
     *
     * <p>Esta prueba fija el comportamiento de hoy. Si alguien corrige la guarda, falla, y este
     * comentario explica por qué debe actualizarse en vez de restaurarse.
     */
    @Test
    void unaSolicitudAvisadaNuncaLlegaAIncumplimiento_defectoConocido() {
        seEncuentra(urgente(RepairRequestStatus.SLA_WARNING, ahora.minus(Duration.ofHours(2)),
                administrador, tecnico));

        scheduler.checkSlas();

        verify(requests, never()).update(any(RepairRequest.class));
        assertThat(events.stream(SlaBreached.class)).isEmpty();
        assertThat(avisosDeTipo(NotificationType.REQUEST_SLA_BREACHED)).isEmpty();
    }

    @TestConfiguration
    static class RelojFijo {
        @Bean
        @Primary
        Clock reloj() {
            return Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}

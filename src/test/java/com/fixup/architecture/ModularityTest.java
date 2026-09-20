package com.fixup.architecture;

import com.fixup.FixupApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ModularityTest {
    @Test
    void shouldRespectModuleBoundaries() {
        ApplicationModules modules = ApplicationModules.of(FixupApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("shared", "identityaccess", "users", "fixers", "properties",
                        "media", "requests", "quotations", "jobs", "notifications", "messaging",
                        "payments", "contracts", "analytics");
        modules.verify();
    }
}

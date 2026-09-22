package com.fixup.requests.infrastructure;

import com.fixup.requests.application.CheckRepairRequestSla;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-UC-08: el planificador es solo el reloj. La decisión de a quién avisar vive en
 * CheckRepairRequestSla, y esta prueba fija justamente eso: que aquí no haya lógica que después
 * nadie encuentre, y que el método anotado con @Scheduled delegue.
 *
 * <p>El reloj está apagado en el perfil de pruebas (fixup.sla.enabled=false) para que el barrido
 * no salte solo en mitad de otra prueba, así que esta clase no se ejercita por contexto y se
 * comprueba aquí, sin Spring.
 */
class SlaCheckSchedulerTest {

    @Test
    void theScheduledMethodOnlyDelegatesToTheSweep() {
        var checkSla = mock(CheckRepairRequestSla.class);
        when(checkSla.sweep()).thenReturn(new CheckRepairRequestSla.SweepResult(2, 1));

        new SlaCheckScheduler(checkSla).checkActiveRequests();

        verify(checkSla).sweep();
    }

    @Test
    void theSweepResultReportsWhatTheSweepDid() {
        var result = new CheckRepairRequestSla.SweepResult(3, 4);
        assertThat(result.warnings()).isEqualTo(3);
        assertThat(result.breaches()).isEqualTo(4);
    }
}

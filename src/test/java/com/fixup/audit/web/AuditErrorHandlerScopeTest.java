package com.fixup.audit.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No regresion del alcance de {@link AuditErrorHandler}: el advice esta acotado a
 * {@link StatusAuditController} porque {@code IllegalArgumentException} es generica y un advice
 * global cambiaria el manejo de errores de los demas modulos.
 *
 * <p>El caso positivo (el filtro invalido de la auditoria si produce 400 INVALID_REQUEST) esta en
 * {@link StatusAuditControllerTest#invalidFiltersReturnBadRequest()}. Aqui se prueba el caso
 * complementario: un {@code IllegalArgumentException} lanzado por el controlador de otro modulo no
 * es reescrito por el advice de auditoria.
 */
class AuditErrorHandlerScopeTest {

    /** Controlador de otro modulo: no es un {@link StatusAuditController}. */
    @RestController
    static class SiblingModuleController {
        @GetMapping("/sibling-module/resource")
        String read() {
            throw new IllegalArgumentException("sibling module rejected the request");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SiblingModuleController())
            .setControllerAdvice(new AuditErrorHandler())
            .build();

    @Test
    void illegalArgumentFromAnotherModuleIsNotRewrittenToTheAuditBadRequest() {
        // Si el advice fuese global, esta peticion se resolveria como 400/INVALID_REQUEST y no
        // afloraria ninguna excepcion. Al estar acotado a StatusAuditController, el modulo ajeno
        // conserva su propio manejo de errores y la excepcion sigue su curso.
        assertThatThrownBy(() -> mvc.perform(get("/sibling-module/resource")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}

package com.fixup.analytics.web;

import com.fixup.analytics.api.MarketIndicatorsUnavailableException;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Cuando no hay ni dato vivo ni valor conocido se responde 503, no un 200 con datos inventados.
 * No expone el proveedor, su URL ni el detalle de la excepción.
 */
// Acotado a este controlador: IllegalArgumentException y ConstraintViolationException son
// genéricas y un advice global cambiaría el manejo de errores de los demás módulos.
@RestControllerAdvice(assignableTypes = MarketIndicatorsController.class)
class AnalyticsErrorHandler {
    @ExceptionHandler(MarketIndicatorsUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(503).body(new ErrorResponse(503, "INDICATORS_UNAVAILABLE",
                "Market indicators are temporarily unavailable for this zone", request.getRequestURI()));
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> invalidZone(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "INVALID_REQUEST",
                "The request is invalid", request.getRequestURI()));
    }
}

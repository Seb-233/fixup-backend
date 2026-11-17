package com.fixup.audit.web;

import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce a 400 el filtro invalido de la consulta administrativa (por ejemplo, pedir un recursoId
 * sin resourceType), que {@link com.fixup.audit.application.ListStatusChanges} rechaza con
 * IllegalArgumentException.
 */
// Acotado a este controlador: IllegalArgumentException es generica y un advice global cambiaria el
// manejo de errores de los demas modulos que hoy no la capturan -- exactamente el mismo criterio que
// AnalyticsErrorHandler aplica sobre MarketIndicatorsController. El 401 y el 403 (sesion ausente o
// sin PLATFORM_ADMIN) los sigue resolviendo ApiExceptionHandler, que es global por diseno.
@RestControllerAdvice(assignableTypes = StatusAuditController.class)
class AuditErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "INVALID_REQUEST",
                exception.getMessage() != null ? exception.getMessage() : "The request is invalid",
                request.getRequestURI()));
    }
}

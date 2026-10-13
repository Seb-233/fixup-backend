package com.fixup.analytics.web;

import com.fixup.analytics.application.BuildExpenseReport;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-13: reportes de gastos de mantenimiento por inmueble y periodo. */
@RestController
@RequestMapping("/analytics/expenses")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid period",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Only owners, real estate managers and platform admins",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class ExpenseReportController {
    private final CurrentActorProvider actors;
    private final BuildExpenseReport buildReport;

    ExpenseReportController(CurrentActorProvider actors, BuildExpenseReport buildReport) {
        this.actors = actors;
        this.buildReport = buildReport;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Paid repair expenses by property, month and specialty, against the previous period")
    BuildExpenseReport.ExpenseReport report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId) {
        return buildReport.execute(actors.currentActor(), from, to, propertyId);
    }

    @GetMapping(value = "/csv", produces = "text/csv")
    @Operation(summary = "Download the expense detail of the period as CSV")
    ResponseEntity<byte[]> csv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId) {
        var report = buildReport.execute(actors.currentActor(), from, to, propertyId);
        // A UTF-8 byte order mark lets spreadsheet programs show the accents in Spanish text correctly.
        var body = ("﻿" + BuildExpenseReport.toCsv(report)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"gastos-" + from + "-a-" + to + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidPeriod(IllegalArgumentException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "INVALID_PERIOD", exception.getMessage(),
                request.getRequestURI()));
    }

    @ExceptionHandler(com.fixup.payments.api.PaymentAccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(403).body(new ErrorResponse(403, "ACCESS_DENIED",
                "You do not have permission to perform this action", request.getRequestURI()));
    }
}

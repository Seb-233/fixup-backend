package com.fixup.analytics.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.ExpenseLedger;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestSnapshot;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-13: el gasto en reparaciones por inmueble, mes y especialidad en un periodo, comparado con
 * el periodo inmediatamente anterior de la misma duración. El gasto es lo que se pagó de verdad
 * (transferencias aprobadas y efectivo recibido, FR-UC-05), no lo cotizado: un reporte de gastos que
 * sumara ofertas inflaría
 * el número con trabajos que nunca se hicieron.
 */
@Service
public class BuildExpenseReport {
    static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");
    static final long MAX_DAYS = 3 * 366;

    private final ExpenseLedger ledger;
    private final PropertyDirectory properties;
    private final RepairRequestDirectory requests;

    BuildExpenseReport(ExpenseLedger ledger, PropertyDirectory properties, RepairRequestDirectory requests) {
        this.ledger = ledger;
        this.properties = properties;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public ExpenseReport execute(CurrentActor actor, LocalDate from, LocalDate to, UUID propertyId) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("from and to are required and from must not be after to");
        }
        if (Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > MAX_DAYS) {
            throw new IllegalArgumentException("A report covers at most three years");
        }
        var start = from.atStartOfDay(COLOMBIA).toInstant();
        var end = to.plusDays(1).atStartOfDay(COLOMBIA).toInstant();
        long days = Duration.between(start, end).toDays();
        var previousStart = start.minus(Duration.ofDays(days));
        var current = ledger.approvedExpenses(actor, start, end);
        var before = ledger.approvedExpenses(actor, previousStart, start);
        var requestIds = new HashSet<UUID>();
        current.forEach(expense -> requestIds.add(expense.requestId()));
        before.forEach(expense -> requestIds.add(expense.requestId()));
        var context = requests.snapshots(requestIds);
        var lines = filter(current.stream().map(expense -> Expense.of(expense, context)).toList(), propertyId);
        var previous = filter(before.stream().map(expense -> Expense.of(expense, context)).toList(), propertyId);

        var propertyIds = new HashSet<UUID>();
        lines.forEach(line -> propertyIds.add(line.propertyId()));
        propertyIds.remove(null);
        var names = properties.names(propertyIds);

        var byProperty = new LinkedHashMap<UUID, long[]>();
        var byMonth = new TreeMap<YearMonth, Long>();
        var bySpecialty = new TreeMap<String, Long>();
        for (var line : lines) {
            var bucket = byProperty.computeIfAbsent(line.propertyId(), key -> new long[2]);
            bucket[0] += line.amount();
            bucket[1]++;
            byMonth.merge(YearMonth.from(line.approvedAt().atZone(COLOMBIA)), line.amount(), Long::sum);
            bySpecialty.merge(line.specialty() == null ? "GENERAL" : line.specialty(), line.amount(), Long::sum);
        }
        // Months with no spending still appear, so a chart of the period has no misleading gaps.
        for (var month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            byMonth.putIfAbsent(month, 0L);
        }

        var propertyTotals = byProperty.entrySet().stream()
                .map(entry -> new ExpenseReport.PropertyTotal(entry.getKey(),
                        entry.getKey() == null ? "Sin inmueble" : names.getOrDefault(entry.getKey(), "Inmueble"),
                        entry.getValue()[0], (int) entry.getValue()[1]))
                .sorted(Comparator.comparingLong(ExpenseReport.PropertyTotal::total).reversed())
                .toList();
        var monthTotals = byMonth.entrySet().stream()
                .map(entry -> new ExpenseReport.MonthTotal(entry.getKey().toString(), entry.getValue()))
                .toList();
        var specialtyTotals = bySpecialty.entrySet().stream()
                .map(entry -> new ExpenseReport.SpecialtyTotal(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(ExpenseReport.SpecialtyTotal::total).reversed())
                .toList();
        var detail = new ArrayList<ExpenseReport.Line>();
        lines.stream().sorted(Comparator.comparing(Expense::approvedAt).reversed())
                .forEach(line -> detail.add(new ExpenseReport.Line(line.paymentId(), line.propertyId(),
                        line.propertyId() == null ? null : names.get(line.propertyId()), line.requestId(),
                        line.specialty(), line.concept(), line.amount(), line.approvedAt())));

        long total = lines.stream().mapToLong(Expense::amount).sum();
        long previousTotal = previous.stream().mapToLong(Expense::amount).sum();
        return new ExpenseReport(from, to, total, lines.size(), previousTotal, previous.size(), propertyTotals,
                monthTotals, specialtyTotals, detail);
    }

    /** Un pago con el inmueble, la especialidad y el concepto de la solicitud que lo originó. */
    record Expense(UUID paymentId, UUID propertyId, UUID requestId, String specialty, String concept, long amount,
            java.time.Instant approvedAt) {

        static Expense of(ExpenseLedger.Expense expense, Map<UUID, RepairRequestSnapshot> context) {
            var request = context.get(expense.requestId());
            var title = request == null || request.title() == null ? "Reparación" : request.title();
            var concept = switch (expense.kind()) {
                case TRANSFER -> title;
                case OUTSTANDING_BALANCE -> "Saldo pendiente: " + title;
                case CASH -> "Efectivo: " + title;
            };
            return new Expense(expense.paymentId(), request == null ? null : request.propertyId(), expense.requestId(),
                    request == null || request.specialty() == null ? null : request.specialty().name(), concept,
                    expense.amount(), expense.paidAt());
        }
    }

    private static List<Expense> filter(List<Expense> lines, UUID propertyId) {
        if (propertyId == null) {
            return lines;
        }
        return lines.stream().filter(line -> Objects.equals(line.propertyId(), propertyId))
                .collect(Collectors.toList());
    }

    public record ExpenseReport(LocalDate from, LocalDate to, long total, int count, long previousPeriodTotal,
            int previousPeriodCount, List<PropertyTotal> byProperty, List<MonthTotal> byMonth,
            List<SpecialtyTotal> bySpecialty, List<Line> lines) {

        public record PropertyTotal(UUID propertyId, String propertyName, long total, int count) {
        }

        public record MonthTotal(String month, long total) {
        }

        public record SpecialtyTotal(String specialty, long total) {
        }

        public record Line(UUID paymentId, UUID propertyId, String propertyName, UUID requestId, String specialty,
                String concept, long amount, java.time.Instant paidAt) {
        }
    }

    /** Detalle en CSV con separador coma y montos en pesos enteros, listo para abrir en una hoja de cálculo. */
    public static String toCsv(ExpenseReport report) {
        var csv = new StringBuilder("fecha_pago,inmueble,especialidad,concepto,valor_cop\n");
        for (var line : report.lines()) {
            csv.append(line.paidAt().atZone(COLOMBIA).toLocalDate()).append(',')
                    .append(escape(line.propertyName())).append(',')
                    .append(escape(line.specialty())).append(',')
                    .append(escape(line.concept())).append(',')
                    .append(line.amount()).append('\n');
        }
        csv.append(",,,TOTAL,").append(report.total()).append('\n');
        return csv.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        var safe = value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")
                ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}

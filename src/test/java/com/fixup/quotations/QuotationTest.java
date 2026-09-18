package com.fixup.quotations;

import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationStatus;
import com.fixup.quotations.domain.Quotation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-UC-18: máquina de estados de la cotización y sus límites de monto y plazo. */
class QuotationTest {
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T15:00:00Z");

    private Quotation submitted() {
        return Quotation.submitted(UUID.randomUUID(), REQUEST, FIXER, 450_000L, 3,
                "Incluye materiales y sellado.", NOW);
    }

    @Test
    void newQuotationStartsSubmitted() {
        var quotation = submitted();
        assertThat(quotation.status()).isEqualTo(QuotationStatus.SUBMITTED);
        assertThat(quotation.isSubmitted()).isTrue();
        assertThat(quotation.amount()).isEqualTo(450_000L);
        assertThat(quotation.estimatedDays()).isEqualTo(3);
    }

    @Test
    void acceptingKeepsThePriceAndMovesTheStatus() {
        var accepted = submitted().accept(NOW);
        assertThat(accepted.status()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(accepted.amount()).isEqualTo(450_000L);
        assertThat(accepted.isSubmitted()).isFalse();
    }

    @Test
    void rejectingClosesTheOffer() {
        assertThat(submitted().reject(NOW).status()).isEqualTo(QuotationStatus.REJECTED);
    }

    @Test
    void decidingTwiceIsRejected() {
        var accepted = submitted().accept(NOW);
        assertThatThrownBy(() -> accepted.reject(NOW))
                .isInstanceOf(QuotationConflictException.class)
                .hasMessageContaining("no longer admits a decision");
        var rejected = submitted().reject(NOW);
        assertThatThrownBy(() -> rejected.accept(NOW))
                .isInstanceOf(QuotationConflictException.class);
    }

    @Test
    void theAmountMustBePositive() {
        assertThatThrownBy(() -> Quotation.submitted(UUID.randomUUID(), REQUEST, FIXER, 0L, 1, null, NOW))
                .isInstanceOf(QuotationConflictException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> Quotation.submitted(UUID.randomUUID(), REQUEST, FIXER, -1L, 1, null, NOW))
                .isInstanceOf(QuotationConflictException.class);
    }

    @Test
    void theEstimateStaysInsideTheAllowedRange() {
        assertThatThrownBy(() -> Quotation.submitted(UUID.randomUUID(), REQUEST, FIXER, 1L, 0, null, NOW))
                .isInstanceOf(QuotationConflictException.class)
                .hasMessageContaining("between 1 and");
        assertThatThrownBy(() -> Quotation.submitted(UUID.randomUUID(), REQUEST, FIXER, 1L,
                Quotation.MAX_ESTIMATED_DAYS + 1, null, NOW))
                .isInstanceOf(QuotationConflictException.class);
        assertThatCode(() -> Quotation.submitted(UUID.randomUUID(), REQUEST, FIXER, 1L,
                Quotation.MAX_ESTIMATED_DAYS, null, NOW)).doesNotThrowAnyException();
    }

    @Test
    void theOfferRemembersWhoSentItAndAgainstWhichRequest() {
        var quotation = submitted();
        assertThat(quotation.fixerUserId()).isEqualTo(FIXER);
        assertThat(quotation.requestId()).isEqualTo(REQUEST);
    }
}

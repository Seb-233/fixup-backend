package com.fixup.media;

import com.fixup.media.api.PortfolioInsufficientPiecesException;
import com.fixup.media.domain.FixerPortfolio;
import com.fixup.media.domain.PortfolioStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixerPortfolioTest {
    private static final UUID FIXER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    @Test
    void newPortfolioStartsInDraft() {
        var portfolio = FixerPortfolio.initialDraft(FIXER, NOW);

        assertThat(portfolio.status()).isEqualTo(PortfolioStatus.DRAFT);
        assertThat(portfolio.isPublished()).isFalse();
        assertThat(portfolio.publishedAt()).isNull();
    }

    @Test
    void twoPhotosDoNotAllowPublication() {
        var portfolio = FixerPortfolio.initialDraft(FIXER, NOW);

        assertThatThrownBy(() -> portfolio.publish(2, NOW))
                .isInstanceOf(PortfolioInsufficientPiecesException.class)
                .hasMessageContaining("at least 3 active visible photos");
        assertThat(portfolio.status()).isEqualTo(PortfolioStatus.DRAFT);
    }

    @Test
    void threePhotosAllowPublication() {
        var portfolio = FixerPortfolio.initialDraft(FIXER, NOW);
        var published = portfolio.publish(3, NOW);

        assertThat(published.status()).isEqualTo(PortfolioStatus.PUBLISHED);
        assertThat(published.isPublished()).isTrue();
        assertThat(published.publishedAt()).isEqualTo(NOW);
    }

    @Test
    void deletingOrHidingAPhotoLeavingTwoRevertsToDraft() {
        var portfolio = FixerPortfolio.initialDraft(FIXER, NOW).publish(3, NOW);
        assertThat(portfolio.isPublished()).isTrue();

        var reverted = portfolio.revertToDraftIfInsufficient(2, NOW.plusSeconds(30));
        assertThat(reverted.status()).isEqualTo(PortfolioStatus.DRAFT);
        assertThat(reverted.isPublished()).isFalse();
    }

    @Test
    void showingAThirdPhotoDoesNotAutomaticallyPublish() {
        var portfolio = FixerPortfolio.initialDraft(FIXER, NOW);
        // Having 3 photos while in DRAFT does not make it published automatically
        var checked = portfolio.revertToDraftIfInsufficient(3, NOW);
        assertThat(checked.status()).isEqualTo(PortfolioStatus.DRAFT);
    }

    @Test
    void unpublishExplicitlyRevertsToDraft() {
        var portfolio = FixerPortfolio.initialDraft(FIXER, NOW).publish(4, NOW);
        var unpublished = portfolio.unpublish(NOW.plusSeconds(60));

        assertThat(unpublished.status()).isEqualTo(PortfolioStatus.DRAFT);
        assertThat(unpublished.isPublished()).isFalse();
    }
}

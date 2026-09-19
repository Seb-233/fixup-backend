package com.fixup.media;

import com.fixup.media.api.PortfolioRuleException;
import com.fixup.media.api.PortfolioVisibility;
import com.fixup.media.domain.PortfolioPiece;
import com.fixup.media.domain.PortfolioPolicy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-UC-17: publication rules of the portfolio, with no Spring context involved. */
class PortfolioPieceTest {
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID MEDIA_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T10:15:30Z");

    private PortfolioPiece piece(String title, String description) {
        return PortfolioPiece.publish(FIXER, MEDIA_ID, title, description, 1, NOW);
    }

    @Test
    void aPublishedPieceStartsPublicAndKeepsItsPosition() {
        var published = piece("Reparación de tubería", "Cambio completo del sifón");

        assertThat(published.visibility()).isEqualTo(PortfolioVisibility.PUBLIC);
        assertThat(published.isPublic()).isTrue();
        assertThat(published.position()).isEqualTo(1);
        assertThat(published.fixerUserId()).isEqualTo(FIXER);
        assertThat(published.mediaId()).isEqualTo(MEDIA_ID);
        assertThat(published.createdAt()).isEqualTo(published.updatedAt());
    }

    @Test
    void thePieceReferencesTheMediaIdAndTrimsText() {
        var published = PortfolioPiece.publish(FIXER, MEDIA_ID,
                "  Instalación  ", "  Antes y después  ", 3, NOW);

        assertThat(published.mediaId()).isEqualTo(MEDIA_ID);
        assertThat(published.title()).isEqualTo("Instalación");
        assertThat(published.description()).isEqualTo("Antes y después");
    }

    @Test
    void aDescriptionIsOptionalAndBlankBecomesAbsent() {
        assertThat(piece("Con título", null).description()).isNull();
        assertThat(piece("Con título", "   ").description()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aPieceWithoutTitleIsRejected(String title) {
        assertThatThrownBy(() -> piece(title, "algo"))
                .isInstanceOf(PortfolioRuleException.class)
                .extracting(problem -> ((PortfolioRuleException) problem).code()).isEqualTo("INVALID_PIECE");
    }

    @Test
    void aPieceWithoutMediaIdIsRejected() {
        assertThatThrownBy(() -> PortfolioPiece.publish(FIXER, null, "t", null, 1, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void titleAndDescriptionHaveAnUpperBound() {
        assertThatThrownBy(() -> piece("t".repeat(PortfolioPolicy.TITLE_MAX + 1), null))
                .isInstanceOf(PortfolioRuleException.class);
        assertThatThrownBy(() -> piece("ok", "d".repeat(PortfolioPolicy.DESCRIPTION_MAX + 1)))
                .isInstanceOf(PortfolioRuleException.class);
        assertThatCode(() -> piece("t".repeat(PortfolioPolicy.TITLE_MAX),
                "d".repeat(PortfolioPolicy.DESCRIPTION_MAX))).doesNotThrowAnyException();
    }

    @Test
    void hidingTakesThePieceOutOfThePublicPortfolioAndShowingPutsItBack() {
        var published = piece("Obra", null);
        var hidden = published.hide(NOW.plusSeconds(60));

        assertThat(hidden.visibility()).isEqualTo(PortfolioVisibility.HIDDEN);
        assertThat(hidden.isPublic()).isFalse();
        assertThat(hidden.id()).isEqualTo(published.id());
        assertThat(hidden.position()).isEqualTo(published.position());
        assertThat(hidden.createdAt()).isEqualTo(published.createdAt());
        assertThat(hidden.updatedAt()).isAfter(published.updatedAt());

        assertThat(hidden.show(NOW.plusSeconds(120)).visibility()).isEqualTo(PortfolioVisibility.PUBLIC);
    }

    @Test
    void repeatingTheCurrentVisibilityIsAConflict() {
        var published = piece("Obra", null);

        assertThatThrownBy(() -> published.show(NOW))
                .isInstanceOf(PortfolioRuleException.class)
                .extracting(problem -> ((PortfolioRuleException) problem).code())
                .isEqualTo("VISIBILITY_UNCHANGED");
        assertThatThrownBy(() -> published.hide(NOW).hide(NOW))
                .isInstanceOf(PortfolioRuleException.class);
    }

    @Test
    void onlyTheOwnerCuratesThePieceAndAStrangerCannotTellItExists() {
        var published = piece("Obra", null);

        assertThatCode(() -> published.requireOwnedBy(FIXER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> published.requireOwnedBy(UUID.randomUUID()))
                .isInstanceOf(PortfolioRuleException.class)
                .extracting(problem -> ((PortfolioRuleException) problem).code()).isEqualTo("PIECE_NOT_FOUND");
    }

    @Test
    void thePortfolioIsCappedAndPositionsAreAppended() {
        assertThatCode(() -> PortfolioPolicy.requireRoomFor(PortfolioPolicy.MAX_PIECES - 1))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PortfolioPolicy.requireRoomFor(PortfolioPolicy.MAX_PIECES))
                .isInstanceOf(PortfolioRuleException.class)
                .extracting(problem -> ((PortfolioRuleException) problem).code()).isEqualTo("PORTFOLIO_FULL");
        assertThat(PortfolioPolicy.nextPosition(0)).isEqualTo(1);
        assertThat(PortfolioPolicy.nextPosition(7)).isEqualTo(8);
    }

    @Test
    void aPositionBelowOneIsRejected() {
        assertThatThrownBy(() -> PortfolioPiece.publish(FIXER, MEDIA_ID, "t", null, 0, NOW))
                .isInstanceOf(PortfolioRuleException.class)
                .extracting(problem -> ((PortfolioRuleException) problem).code()).isEqualTo("INVALID_POSITION");
    }
}

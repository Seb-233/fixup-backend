package com.fixup.media.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.media.domain.PortfolioPieces;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-17: the ideas feed. Every public piece of every published portfolio, a page at a time,
 * so clients can browse finished work before asking for a quotation. Draft portfolios and hidden
 * pieces never appear, the same rule as the public portfolio of one fixer.
 */
@Service
public class BrowsePortfolioFeed {
    public static final int MAX_PAGE_SIZE = 48;
    public static final int MAX_FIXER_FILTER = 200;

    private final PortfolioPieces pieces;
    private final PortfolioViewResolver resolver;

    BrowsePortfolioFeed(PortfolioPieces pieces, PortfolioViewResolver resolver) {
        this.pieces = pieces;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public FeedPage execute(CurrentActor reader, int page, int size, Collection<UUID> fixerFilter) {
        if (reader == null || reader.status() != UserStatus.ACTIVE) {
            throw new AccessDeniedException("Account is not active or operation is not allowed");
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Set<UUID> filter = fixerFilter == null ? null : Set.copyOf(fixerFilter.stream().limit(MAX_FIXER_FILTER).toList());
        var slice = pieces.findPublishedFeed(filter, safePage, safeSize);
        var items = slice.pieces().stream()
                .map(piece -> new FeedItem(piece.fixerUserId(), resolver.toView(piece)))
                .toList();
        return new FeedPage(items, safePage, slice.hasMore());
    }

    public record FeedItem(UUID fixerUserId, PortfolioPieceView piece) {
    }

    public record FeedPage(List<FeedItem> items, int page, boolean hasMore) {
    }
}

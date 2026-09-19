package com.fixup.media.application;

import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.PortfolioPiece;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PortfolioViewResolver {
    public static final Duration READ_EXPIRATION = Duration.ofMinutes(5);

    private final MediaAssets mediaAssets;
    private final ObjectStorage objectStorage;

    public PortfolioViewResolver(MediaAssets mediaAssets, ObjectStorage objectStorage) {
        this.mediaAssets = mediaAssets;
        this.objectStorage = objectStorage;
    }

    public PortfolioPieceView toView(PortfolioPiece piece) {
        MediaAsset asset = mediaAssets.findById(piece.mediaId()).orElse(null);
        String readUrl = "";
        Instant expiresAt = Instant.now().plus(READ_EXPIRATION);
        if (asset != null) {
            var readTicket = objectStorage.createReadTicket(asset.objectKey(), READ_EXPIRATION);
            readUrl = readTicket.readUrl();
            expiresAt = readTicket.expiresAt();
        }
        return new PortfolioPieceView(
                piece.id(),
                piece.mediaId(),
                piece.title(),
                piece.description(),
                piece.position(),
                piece.visibility(),
                readUrl,
                expiresAt,
                piece.createdAt());
    }

    public List<PortfolioPieceView> toViews(List<PortfolioPiece> pieces) {
        return pieces.stream().map(this::toView).toList();
    }
}

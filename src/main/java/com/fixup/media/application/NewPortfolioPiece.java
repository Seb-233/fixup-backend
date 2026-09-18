package com.fixup.media.application;

import com.fixup.media.api.PortfolioPieceKind;

/** What the client sends to publish: a storage key, never the file itself. */
public record NewPortfolioPiece(PortfolioPieceKind kind, String storageKey, String title, String description) {
}

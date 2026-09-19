package com.fixup.media.api;

public class PieceNotFoundException extends MediaException {
    public PieceNotFoundException(String message) {
        super(404, "PIECE_NOT_FOUND", message);
    }
}

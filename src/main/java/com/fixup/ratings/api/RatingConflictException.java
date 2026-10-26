package com.fixup.ratings.api;

/** The job cannot be rated in its current state, or it was already rated. */
public class RatingConflictException extends RuntimeException {
    private final String code;

    public RatingConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

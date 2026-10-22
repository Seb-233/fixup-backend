package com.fixup.ratings.api;

/** Only the client of a completed job can rate it. */
public class RatingAccessDeniedException extends RuntimeException {
    public RatingAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}

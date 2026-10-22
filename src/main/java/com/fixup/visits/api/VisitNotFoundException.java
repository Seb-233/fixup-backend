package com.fixup.visits.api;

/** The visit does not exist, or the caller is not entitled to learn that it does. */
public class VisitNotFoundException extends RuntimeException {
    public VisitNotFoundException() {
        super("The visit does not exist");
    }
}

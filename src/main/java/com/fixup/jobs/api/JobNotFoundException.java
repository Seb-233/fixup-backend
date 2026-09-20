package com.fixup.jobs.api;

/** The job does not exist, or the caller is not entitled to learn that it does. */
public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException() {
        super("The job does not exist");
    }
}

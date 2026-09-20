package com.fixup.jobs.api;

/** The actor is neither the fixer executing the job nor the owner who pays for it. */
public class JobAccessDeniedException extends RuntimeException {
    public JobAccessDeniedException() {
        super("You do not have permission to perform this action");
    }
}

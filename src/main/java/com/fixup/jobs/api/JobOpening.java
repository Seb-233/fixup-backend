package com.fixup.jobs.api;

/** Opens the assigned job that belongs to an accepted cash quotation. */
public interface JobOpening {
    void open(JobOpeningCommand command);
}

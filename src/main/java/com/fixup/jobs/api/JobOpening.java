package com.fixup.jobs.api;

/** Opens the assigned job authorized by the payment/quotation workflow. */
public interface JobOpening {
    void open(JobOpeningCommand command);
}

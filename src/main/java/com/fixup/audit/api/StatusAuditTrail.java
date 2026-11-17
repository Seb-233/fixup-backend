package com.fixup.audit.api;

public interface StatusAuditTrail {
    void record(StatusChangeCommand command);
}

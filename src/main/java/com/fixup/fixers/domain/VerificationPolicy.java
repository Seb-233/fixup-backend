package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import java.util.Set;
import java.util.TreeSet;

/** FR-UC-16: la revisión solo se abre cuando están los documentos obligatorios. */
public final class VerificationPolicy {
    public static final Set<FixerVerificationDocumentType> REQUIRED = Set.of(
            FixerVerificationDocumentType.ID_CARD, FixerVerificationDocumentType.TRADE_CERTIFICATE);

    private VerificationPolicy() {
    }

    /** Documents still expected, in a stable order so the client can show them. */
    public static Set<FixerVerificationDocumentType> missing(Set<FixerVerificationDocumentType> submitted) {
        var pending = new TreeSet<>(REQUIRED);
        pending.removeAll(submitted);
        return Set.copyOf(pending);
    }

    public static boolean isComplete(Set<FixerVerificationDocumentType> submitted) {
        return missing(submitted).isEmpty();
    }
}

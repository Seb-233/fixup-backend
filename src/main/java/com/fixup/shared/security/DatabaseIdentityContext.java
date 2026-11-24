package com.fixup.shared.security;

import java.util.Optional;
import java.util.function.Supplier;

/** Lexically scoped authorization for identity resolution, established before transaction begin.
 * Only trusted identity services may enter this scope after verifying the authenticated subject.
 * Publishing this metadata does not itself restrict SELECT policies that use USING(true).
 */
public final class DatabaseIdentityContext {
    private static final ThreadLocal<String> SUBJECT = new ThreadLocal<>();

    private DatabaseIdentityContext() { }

    public static Optional<String> currentSubject() {
        return Optional.ofNullable(SUBJECT.get());
    }

    public static void clear() {
        SUBJECT.remove();
    }

    public static <T> T withSubject(String subject, Supplier<T> work) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Identity subject is required");
        }
        var previous = SUBJECT.get();
        SUBJECT.set(subject);
        try {
            return work.get();
        } finally {
            if (previous == null) SUBJECT.remove();
            else SUBJECT.set(previous);
        }
    }
}

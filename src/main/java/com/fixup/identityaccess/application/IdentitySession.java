package com.fixup.identityaccess.application;

import java.util.function.Supplier;

/** Isolated transaction authorized for the authenticated subject before actor resolution.
 * Callers must filter their queries: identity SELECT policies may use USING(true).
 * The callback is trusted server code, not a database sandbox bound to one subject.
 */
public interface IdentitySession {
    <T> T execute(String subject, Supplier<T> work);
    <T> T read(String subject, Supplier<T> work);
}

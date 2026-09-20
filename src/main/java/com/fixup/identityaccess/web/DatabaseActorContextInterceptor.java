package com.fixup.identityaccess.web;

import com.fixup.shared.security.DatabaseActorContext;

import com.fixup.identityaccess.api.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Ensures the authenticated actor is resolved and published to {@link DatabaseActorContext} BEFORE
 * any {@code @Transactional} service method starts a transaction, so that
 * {@link com.fixup.shared.configuration.RlsSessionTransactionManager} can read it and activate the
 * PostgreSQL Row-Level Security session variables at transaction start time.
 *
 * <p>Without this, a pattern like:
 * <pre>
 *   {@literal @}Transactional
 *   public Result execute(...) {
 *       var actor = currentActorProvider.currentActor(); // fills DatabaseActorContext
 *       ...                                              // but the transaction already started!
 *   }
 * </pre>
 * would leave {@code DatabaseActorContext} empty when the transaction manager runs
 * {@code doBegin()}, so RLS would not activate for that transaction.
 *
 * <p>This interceptor runs in {@code preHandle}, before the controller method (and therefore before
 * any transactional service) is invoked. It only resolves the actor when the request carries a
 * verified JWT -- unauthenticated requests and background jobs are left untouched.
 *
 * <p>{@code afterCompletion} still clears the context to prevent leaking between pooled threads.
 */
final class DatabaseActorContextInterceptor implements HandlerInterceptor {
    private final CurrentActorProvider currentActorProvider;

    DatabaseActorContextInterceptor(CurrentActorProvider currentActorProvider) {
        this.currentActorProvider = currentActorProvider;
    }

    /**
     * Eagerly resolves the actor so {@code DatabaseActorContext} is populated before any
     * {@code @Transactional} method opens a transaction.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken && auth.isAuthenticated()) {
            // Resolving currentActor() as a side effect populates DatabaseActorContext; the returned
            // value is discarded here because controllers will call it again through their own path.
            // The second call is a no-op at the provider level: the context is already set, and
            // UserLookup goes through the same transaction-less Spring Security machinery.
            currentActorProvider.currentActor();
        }
        return true;
    }

    /** Removes the actor from the thread-local after each request to avoid leaking between threads. */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        DatabaseActorContext.clear();
    }
}
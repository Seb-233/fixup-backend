package com.fixup.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Clears the actor left over from a previous request on this pooled worker thread. Without this,
 * a thread that served an authenticated request could leak that actor into whatever it handles next.
 */
final class DatabaseActorContextInterceptor implements HandlerInterceptor {
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        DatabaseActorContext.clear();
    }
}

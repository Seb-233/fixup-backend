package com.fixup.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Clears request-scoped database authorization even for bootstrap, rejected JWTs and MVC errors. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class DatabaseSessionContextCleanupFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        clear();
        try {
            chain.doFilter(request, response);
        } finally {
            clear();
        }
    }

    private void clear() {
        DatabaseActorContext.clear();
        DatabaseIdentityContext.clear();
    }
}

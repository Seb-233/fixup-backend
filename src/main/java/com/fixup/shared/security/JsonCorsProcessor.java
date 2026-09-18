package com.fixup.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.shared.errors.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.DefaultCorsProcessor;

/** Keeps rejected preflight responses consistent with other security errors. */
class JsonCorsProcessor implements CorsProcessor {
    private final ObjectMapper mapper;

    JsonCorsProcessor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean processRequest(CorsConfiguration configuration, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        return new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(ServerHttpResponse rejected) throws IOException {
                rejected.setStatusCode(HttpStatus.FORBIDDEN);
                rejected.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                mapper.writeValue(rejected.getBody(), new ErrorResponse(403, "ACCESS_DENIED",
                        "You do not have permission to perform this action", request.getRequestURI()));
                rejected.flush();
            }
        }.processRequest(configuration, request, response);
    }
}

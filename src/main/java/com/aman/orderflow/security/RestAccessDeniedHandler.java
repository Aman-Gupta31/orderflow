package com.aman.orderflow.security;

import com.aman.orderflow.common.response.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Runs when a request is authenticated but fails a URL-level role rule declared
 * in SecurityConfig (e.g. a CUSTOMER calling an ADMIN-only endpoint). This is
 * distinct from GlobalExceptionHandler's own AccessDeniedException mapping,
 * which covers ownership checks thrown from inside service/controller code -
 * see docs/learning-guide/05-security-jwt-auth.md for why both exist.
 * Registered as a @Bean in SecurityConfig (not @Component) so it's reliably
 * included whenever SecurityConfig is, including under @WebMvcTest slices.
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(HttpStatus.FORBIDDEN.value(), "Forbidden",
                "You do not have permission to perform this action", request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}

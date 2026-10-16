package com.aman.orderflow.security;

import com.aman.orderflow.common.response.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Runs when Spring Security itself rejects a request for lacking valid
 * authentication entirely (missing/invalid/expired token) - this happens inside
 * the security filter chain, before the request ever reaches a controller, so
 * GlobalExceptionHandler (which only sees exceptions thrown during MVC dispatch)
 * can't produce this response; this is the dedicated equivalent for that layer.
 * Registered as a @Bean in SecurityConfig (not @Component) so it's reliably
 * included whenever SecurityConfig is, including under @WebMvcTest slices.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                "A valid access token is required", request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}

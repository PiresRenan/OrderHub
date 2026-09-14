package io.github.piresrenan.orderhub.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Keeps Resource Server diagnostics and untrusted bearer material outside the public failure contract. */
final class SanitizedBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {
    /** Returns the same bounded challenge for absent, invalid and unbound credentials without reflecting their cause. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        response.setStatus(401);
        response.setContentType("application/problem+json");
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"urn:orderhub:problem:authentication-required\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Valid bearer authentication is required\",\"code\":\"authentication-required\"}");
    }
}

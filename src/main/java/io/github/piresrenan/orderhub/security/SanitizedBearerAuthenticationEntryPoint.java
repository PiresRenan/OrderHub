package io.github.piresrenan.orderhub.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Keeps Resource Server diagnostics and untrusted bearer material outside the public failure contract. */
final class SanitizedBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {
    /** Separates temporary identity-store unavailability from invalid credentials without reflecting either cause. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        if (exception instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauth
                && "temporarily_unavailable".equals(oauth.getError().getErrorCode())) {
            response.setStatus(503);
            response.setContentType("application/problem+json");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", "1");
            response.getWriter().write("{\"type\":\"urn:orderhub:problem:authentication-unavailable\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"Identity verification is temporarily unavailable\",\"code\":\"authentication-unavailable\"}");
            return;
        }
        response.setStatus(401);
        response.setContentType("application/problem+json");
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"urn:orderhub:problem:authentication-required\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Valid bearer authentication is required\",\"code\":\"authentication-required\"}");
    }
}

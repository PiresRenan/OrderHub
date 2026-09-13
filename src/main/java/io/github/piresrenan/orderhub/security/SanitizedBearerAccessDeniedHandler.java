package io.github.piresrenan.orderhub.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Gives security-filter denials the same bounded public shape as controller denials. */
final class SanitizedBearerAccessDeniedHandler implements AccessDeniedHandler {
    /** Reports denial without reflecting tenant selectors, principal identifiers or internal causes. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        response.setStatus(403);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"urn:orderhub:problem:access-denied\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Access is denied\",\"code\":\"access-denied\"}");
    }
}

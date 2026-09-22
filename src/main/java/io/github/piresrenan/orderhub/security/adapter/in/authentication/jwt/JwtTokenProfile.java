package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import java.net.URI;
import java.util.List;

/** Server-selected provider policy; JWT claims never select their own trust profile. */
public enum JwtTokenProfile {
    GENERIC,
    COGNITO;

    /** Cognito resource binding uses an API URL, never the app client ID as an audience fallback. */
    public void validateAudience(String audience) {
        if (this == GENERIC) { return; }
        try {
            var uri = URI.create(audience);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535) {
                return;
            }
        } catch (IllegalArgumentException exception) {
            // Reject without reflecting private provider configuration.
        }
        throw new IllegalArgumentException("Cognito JWT audience must be an HTTPS resource URL");
    }

    /** Cognito admits only explicitly configured App Clients; a client ID is never audience or business authority. */
    public List<String> validateAllowedClientIds(List<String> allowedClientIds) {
        var clients = allowedClientIds == null ? List.<String>of() : List.copyOf(allowedClientIds);
        if (clients.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("JWT allowed client IDs must not be blank");
        }
        if (this == COGNITO && clients.isEmpty()) {
            throw new IllegalArgumentException("Cognito JWT trust requires allowed client IDs");
        }
        if (this == GENERIC && !clients.isEmpty()) {
            throw new IllegalArgumentException("JWT allowed client IDs apply only to the Cognito profile");
        }
        return clients;
    }
}

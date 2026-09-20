package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import java.net.URI;

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
}

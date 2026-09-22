package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit overlap for provider migration; tokens never choose a remote trust endpoint. */
@ConfigurationProperties(prefix = "orderhub.security.jwt")
public record AdditionalJwtTrustProperties(List<Provider> additionalIssuers) {
    /** Freezes the explicit allowlist and rejects duplicate issuer trust instead of silently shadowing it. */
    public AdditionalJwtTrustProperties {
        additionalIssuers = additionalIssuers == null ? List.of() : List.copyOf(additionalIssuers);
        if (additionalIssuers.stream().map(Provider::issuer).distinct().count() != additionalIssuers.size()) {
            throw new IllegalArgumentException("JWT trusted issuers must be unique");
        }
    }
    public record Provider(String issuer, String jwkSetUri, JwtTokenProfile tokenProfile, List<String> allowedClientIds) {
        /** Retains the existing generic provider overlap contract for explicit Java construction. */
        public Provider(String issuer, String jwkSetUri) {
            this(issuer, jwkSetUri, JwtTokenProfile.GENERIC, List.of());
        }
        /** Requires both server-owned trust endpoints; incomplete migration configuration must fail startup. */
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Provider {
            if (issuer == null || issuer.isBlank() || jwkSetUri == null || jwkSetUri.isBlank()) {
                throw new IllegalArgumentException("JWT provider trust configuration is incomplete");
            }
            if (tokenProfile == null) {
                throw new IllegalArgumentException("JWT provider token profile is required");
            }
            allowedClientIds = tokenProfile.validateAllowedClientIds(allowedClientIds);
        }
    }
}

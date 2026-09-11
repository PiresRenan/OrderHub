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
    public record Provider(String issuer, String jwkSetUri) {
        /** Requires both server-owned trust endpoints; incomplete migration configuration must fail startup. */
        public Provider {
            if (issuer == null || issuer.isBlank() || jwkSetUri == null || jwkSetUri.isBlank()) {
                throw new IllegalArgumentException("JWT provider trust configuration is incomplete");
            }
        }
    }
}

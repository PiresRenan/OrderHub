package io.github.piresrenan.orderhub.security;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit browser trust, independent of bearer identity and business authority. */
@ConfigurationProperties(prefix = "orderhub.security.cors")
public record BrowserCorsProperties(List<String> allowedOrigins) {
    /** Keeps exact origins; insecure HTTP is limited to literal loopback development hosts. */
    public BrowserCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (allowedOrigins.stream().distinct().count() != allowedOrigins.size()) {
            throw new IllegalArgumentException("CORS allowed origins must be unique");
        }
        for (var origin : allowedOrigins) {
            if (!validOrigin(origin)) {
                throw new IllegalArgumentException("CORS allowed origins must be exact HTTPS origins or HTTP loopback origins");
            }
        }
    }

    /** Accepts one serialized origin without paths, patterns or ambiguous authority syntax. */
    private static boolean validOrigin(String origin) {
        try {
            var uri = URI.create(origin);
            var host = uri.getHost();
            var scheme = uri.getScheme();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawPath() == null || !uri.getRawPath().isEmpty()
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                return false;
            }
            // Require the serialized origin shape; reject a trailing colon or other authority ambiguity.
            var authority = host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            if (!origin.equals(scheme + "://" + authority)) { return false; }
            return "https".equals(scheme) || ("http".equals(scheme)
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(host));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

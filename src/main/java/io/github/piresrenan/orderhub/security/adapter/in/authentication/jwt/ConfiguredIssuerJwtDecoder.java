package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import java.util.Map;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import com.nimbusds.jwt.SignedJWT;

/** The untrusted issuer only selects a fixed configured decoder; that decoder verifies every trust claim. */
public final class ConfiguredIssuerJwtDecoder implements JwtDecoder {
    private final Map<String, JwtDecoder> decoders;
    /** Copies the server-owned decoder allowlist; tokens cannot extend provider trust. */
    public ConfiguredIssuerJwtDecoder(Map<String, JwtDecoder> decoders) { this.decoders = Map.copyOf(decoders); }
    /** Uses untrusted issuer only for fixed routing, then delegates every trust check to the selected decoder. */
    @Override public Jwt decode(String token) {
        String issuer;
        try { issuer = SignedJWT.parse(token).getJWTClaimsSet().getIssuer(); }
        catch (java.text.ParseException | IllegalArgumentException exception) { throw new BadJwtException("Invalid bearer token"); }
        var decoder = issuer == null ? null : decoders.get(issuer);
        if (decoder == null) { throw new BadJwtException("Invalid bearer token"); }
        return decoder.decode(token);
    }
}

package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import io.github.piresrenan.orderhub.security.application.model.VerifiedExternalIdentity;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.VerifiedExternalIdentityAuthenticationToken;

/** Called only after the same configured Resource Server decoder and validators accept the JWT. */
public final class VerifiedExternalIdentityJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    /** Projects only structurally valid issuer/subject after decoder verification; claims grant no business authority. */
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        if (jwt == null) { throw new BadCredentialsException("Bearer authentication failed"); }
        try { return new VerifiedExternalIdentityAuthenticationToken(new VerifiedExternalIdentity(jwt.getClaimAsString("iss"), jwt.getSubject())); }
        catch (IllegalArgumentException exception) { throw new BadCredentialsException("Bearer authentication failed"); }
    }
}

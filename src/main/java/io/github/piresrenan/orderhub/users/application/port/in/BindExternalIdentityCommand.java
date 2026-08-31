package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/**
 * Carries the identities required to associate an externally authenticated
 * principal with an internal OrderHub User.
 *
 * <p>
 * Issuer and subject are transported exactly as supplied. This application
 * contract performs no provider-specific normalization.
 * </p>
 *
 * @param issuer  exact external identity issuer
 * @param subject exact external identity subject
 * @param userId  internal OrderHub User identifier
 */
public record BindExternalIdentityCommand(
        String issuer,
        String subject,
        UUID userId) {
}

package io.github.piresrenan.orderhub.workforce.domain.model;

import java.util.Optional;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/**
 * Current workforce-derived organizational authority ceiling.
 *
 * <p>
 * This value grants no permission by itself. It constrains which authorization
 * permissions may become effective for the Staff relationship.
 * </p>
 */
public final class EffectiveWorkforceAuthority {

    private final Optional<AuthorityBand> authorityBand;

    private final PermissionEnvelope permissionEnvelope;

    private EffectiveWorkforceAuthority(
            Optional<AuthorityBand> authorityBand,
            PermissionEnvelope permissionEnvelope) {

        this.authorityBand = authorityBand;
        this.permissionEnvelope = permissionEnvelope;
    }

    public static EffectiveWorkforceAuthority none() {

        return new EffectiveWorkforceAuthority(
                Optional.empty(),
                PermissionEnvelope.none());
    }

    public static EffectiveWorkforceAuthority active(
            AuthorityBand authorityBand,
            PermissionEnvelope permissionEnvelope) {

        if (authorityBand == null) {
            throw new IllegalArgumentException(
                    "Authority band is required");
        }

        if (permissionEnvelope == null) {
            throw new IllegalArgumentException(
                    "Permission envelope is required");
        }

        return new EffectiveWorkforceAuthority(
                Optional.of(
                        authorityBand),
                permissionEnvelope);
    }

    public Optional<AuthorityBand> authorityBand() {

        return authorityBand;
    }

    public PermissionEnvelope permissionEnvelope() {

        return permissionEnvelope;
    }
}

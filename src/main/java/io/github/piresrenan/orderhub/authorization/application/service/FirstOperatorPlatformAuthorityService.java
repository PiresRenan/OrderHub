package io.github.piresrenan.orderhub.authorization.application.service;

import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.application.port.in.bootstrap.FirstOperatorPlatformAuthorityUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantMutationResult;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.FirstOperatorAuthorityRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

/**
 * Applies the frozen initial Platform grant of ADR-0022 in the caller transaction.
 *
 * <p>No administrative grant audit row is written: that evidence names an
 * already-authorized actor, and no such actor exists before the first
 * operator. The bootstrap ceremony records its own attribution in the same
 * transaction instead.</p>
 */
public final class FirstOperatorPlatformAuthorityService implements FirstOperatorPlatformAuthorityUseCase {

    /** The only permission the first operator receives; the first-Staff ceremony requires exactly this. */
    static final PermissionCode INITIAL_PERMISSION = PermissionCode.PLATFORM_TENANTS_MANAGE;

    private final FirstOperatorAuthorityRepository authority;
    private final AdministrativeGrantRepository grants;

    /** Requires the owner-local repositories; construction performs no mutation. */
    public FirstOperatorPlatformAuthorityService(FirstOperatorAuthorityRepository authority, AdministrativeGrantRepository grants) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    /** Delegates the transaction-bound Platform authority presence check to the owner repository. */
    @Override
    public boolean platformAuthorityExists() {
        return authority.platformAuthorityExists();
    }

    /** Grants exactly the initial permission; an already-present grant means the caller's precondition was false. */
    @Override
    public void establishFirstOperatorAuthority(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        // Re-checked in the same transaction so the grant can never join existing Platform authority.
        if (authority.platformAuthorityExists()) {
            throw new IllegalStateException("Platform authority already exists");
        }
        var result = grants.grant(new AdministrativeGrant(userId, AdministrativeScope.platform(), INITIAL_PERMISSION));
        if (result != AdministrativeGrantMutationResult.GRANTED) {
            throw new IllegalStateException("Initial Platform grant was not newly applied");
        }
    }
}

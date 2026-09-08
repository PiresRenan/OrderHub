package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.application.port.in.current.AuthorizeCurrentTenantActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.current.TenantAuthorizationUnavailableException;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.StaffAuthorizationUnavailableException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePermissionEnvelopeRepository;

/** Supplies current workforce facts to the existing kernel without resolving foreign roles itself. */
public final class StaffTenantAuthorizationService implements AuthorizeStaffTenantActionUseCase {

    private final AuthorizeCurrentTenantActionUseCase authorization;
    private final WorkforcePermissionEnvelopeRepository envelopes;

    /** Requires the kernel and a workforce-owned ceiling source; neither is caller-controlled state. */
    public StaffTenantAuthorizationService(AuthorizeCurrentTenantActionUseCase authorization,
            WorkforcePermissionEnvelopeRepository envelopes) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
    }

    /** Delegates role policy with a lazy owner lookup so both authorities share one snapshot. */
    @Override
    public AuthorizationDecision authorize(UUID userId, UUID tenantId, PermissionCode permission) {
        var request = new TenantAuthorizationRequest(userId, AuthorizationPersona.STAFF,
                new TenantAuthorizationScope(tenantId), permission);
        if (!permission.supports(AuthorizationPersona.STAFF)) {
            return AuthorizationDecision.DENY;
        }
        try {
            return authorization.authorizeCurrent(request, envelopes::find);
        } catch (TenantAuthorizationUnavailableException exception) {
            throw new StaffAuthorizationUnavailableException(exception);
        }
    }
}

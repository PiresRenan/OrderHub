package io.github.piresrenan.orderhub.users.application.service;

import java.util.Objects;

import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipEnsureResult;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

/**
 * Users-owned desired-state application service for one exact User/Tenant
 * membership.
 *
 * <p>An absent membership is established ACTIVE. An already operational
 * membership is idempotent desired-state success. Existing non-operational
 * lifecycle state is preserved and is never reactivated implicitly.</p>
 *
 * <p>Concurrent establishment is reconciled only when durable uniqueness
 * reports that the exact membership pair already exists. The service then
 * re-reads Users-owned state and applies the same operational classification.
 * Other persistence failures remain fail-closed and are propagated.</p>
 */
public final class EnsureActiveTenantMembershipService
        implements EnsureActiveTenantMembershipUseCase {

    private final TenantMembershipRepository tenantMembershipRepository;

    public EnsureActiveTenantMembershipService(
            TenantMembershipRepository tenantMembershipRepository) {

        this.tenantMembershipRepository =
                Objects.requireNonNull(
                        tenantMembershipRepository,
                        "tenantMembershipRepository");
    }

    @Override
    public TenantMembershipEnsureResult ensureActive(
            EstablishTenantMembershipCommand command) {

        Objects.requireNonNull(
                command,
                "command");

        var existing =
                tenantMembershipRepository.find(
                        command.userId(),
                        command.tenantId());

        if (existing.isPresent()) {
            return classify(
                    existing.get());
        }

        try {

            tenantMembershipRepository.save(
                    TenantMembership.create(
                            command.userId(),
                            command.tenantId()));

            return new TenantMembershipEnsureResult.Operational();

        } catch (TenantMembershipAlreadyExistsException duplicate) {

            var concurrentState =
                    tenantMembershipRepository.find(
                            command.userId(),
                            command.tenantId());

            if (concurrentState.isEmpty()) {
                throw duplicate;
            }

            return classify(
                    concurrentState.get());
        }
    }

    private TenantMembershipEnsureResult classify(
            TenantMembership membership) {

        return membership.isOperationallyActive()
                ? new TenantMembershipEnsureResult.Operational()
                : new TenantMembershipEnsureResult.NonOperational();
    }
}

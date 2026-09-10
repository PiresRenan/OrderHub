package io.github.piresrenan.orderhub.customers.application.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerAccountLinkingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkIssuance;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkUnavailableException;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerLinkProofRepository;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerLinkTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveQuery;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipEnsureResult;

/** A Customer proof grants only an exact account relationship; it never resolves provider identity or grants Staff roles. */
public final class CustomerAccountLinkingService implements CustomerAccountLinkingUseCase {
    private final CustomerLinkProofRepository proofs;
    private final CustomerLinkTransactionExecutor transaction;
    private final AuthorizeStaffTenantActionUseCase authority;
    private final IsTenantMembershipOperationallyActiveUseCase memberships;
    private final EnsureActiveTenantMembershipUseCase ensureMembership;
    private final FindTenantOperationalStateUseCase tenants;
    private final SecureRandom random = new SecureRandom();

    public CustomerAccountLinkingService(CustomerLinkProofRepository proofs, CustomerLinkTransactionExecutor transaction,
            AuthorizeStaffTenantActionUseCase authority, IsTenantMembershipOperationallyActiveUseCase memberships,
            EnsureActiveTenantMembershipUseCase ensureMembership, FindTenantOperationalStateUseCase tenants) {
        this.proofs = Objects.requireNonNull(proofs);
        this.transaction = Objects.requireNonNull(transaction);
        this.authority = Objects.requireNonNull(authority);
        this.memberships = Objects.requireNonNull(memberships);
        this.ensureMembership = Objects.requireNonNull(ensureMembership);
        this.tenants = Objects.requireNonNull(tenants);
    }

    @Override public CustomerLinkIssuance issue(UUID actor, UUID tenant, UUID customer, UUID operation, UUID correlation) {
        Objects.requireNonNull(customer); Objects.requireNonNull(operation); Objects.requireNonNull(correlation);
        return transaction.execute(() -> {
            requireManager(actor, tenant);
            var entropy = new byte[32];
            random.nextBytes(entropy);
            var digest = sha256(entropy);
            try {
                var result = proofs.create(actor, tenant, customer, operation, correlation, digest);
                if (!result.created()) { return new CustomerLinkIssuance.Replay(result.proof().proofId()); }
                proofs.append(result.proof(), actor, null, "ISSUED", correlation);
                return new CustomerLinkIssuance.Issued(result.proof().proofId(),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(entropy), result.proof().expiresAt());
            } finally { Arrays.fill(entropy, (byte) 0); Arrays.fill(digest, (byte) 0); }
        });
    }

    @Override public UUID consume(UUID trustedUser, UUID tenant, String credential) {
        Objects.requireNonNull(trustedUser); Objects.requireNonNull(tenant);
        var digest = decodeDigest(credential);
        try {
            return transaction.execute(() -> {
                var proof = proofs.consume(tenant, digest).orElseThrow(CustomerLinkUnavailableException::new);
                // Point-in-time current authority, matching the established Staff action contract.
                requireManager(proof.issuerUserId(), tenant);
                if (!(ensureMembership.ensureActive(new EstablishTenantMembershipCommand(trustedUser, tenant))
                        instanceof TenantMembershipEnsureResult.Operational)) { throw new CustomerLinkUnavailableException(); }
                proofs.bind(proof, trustedUser);
                proofs.append(proof, trustedUser, trustedUser, "CONSUMED", proof.correlationId());
                return proof.customerId();
            });
        } finally { Arrays.fill(digest, (byte) 0); }
    }

    @Override public boolean cancel(UUID actor, UUID tenant, UUID proofId, UUID correlation) {
        Objects.requireNonNull(proofId); Objects.requireNonNull(correlation);
        return transaction.execute(() -> {
            requireManager(actor, tenant);
            var proof = proofs.cancel(tenant, proofId);
            if (proof.isEmpty()) { return false; }
            // Revalidate after any row-lock wait; failures roll back the terminal transition.
            requireManager(actor, tenant);
            proofs.append(proof.get(), actor, null, "CANCELLED", correlation);
            return true;
        });
    }

    private void requireManager(UUID actor, UUID tenant) {
        Objects.requireNonNull(actor); Objects.requireNonNull(tenant);
        if (!memberships.isOperationallyActive(new IsTenantMembershipOperationallyActiveQuery(actor, tenant))
                || tenants.find(new FindTenantOperationalStateQuery(tenant)).orElse(null) != TenantOperationalState.ACTIVE
                || authority.authorize(actor, tenant, PermissionCode.TENANT_MEMBERS_MANAGE) != AuthorizationDecision.ALLOW) {
            throw new CustomerLinkUnavailableException();
        }
    }

    private static byte[] decodeDigest(String value) {
        if (value == null || value.length() != 43) { throw new CustomerLinkUnavailableException(); }
        byte[] decoded;
        try { decoded = Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException exception) { throw new CustomerLinkUnavailableException(); }
        try {
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
                throw new CustomerLinkUnavailableException();
            }
            return sha256(decoded);
        } finally { Arrays.fill(decoded, (byte) 0); }
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}

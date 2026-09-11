package io.github.piresrenan.orderhub.workforce.application.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipEnsureResult;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningCompletion;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;

/**
 * Coordinates a verified external identity and one-time workforce proof. Every
 * downstream failure escapes the single authoritative transaction, including
 * authorization, membership conflict, materialization and required evidence.
 * There is no compensation or independent commit in this application boundary.
 */
public final class StaffProvisioningConsumptionService implements ConsumeStaffProvisioningUseCase {
    private final StaffProvisioningIntentRepository intents;
    private final ResolveOrCreateExternalUserUseCase users;
    private final EnsureActiveTenantMembershipUseCase memberships;
    private final StaffMaterializationRepository staff;
    private final StaffProvisioningCompletion completion;
    private final WorkforceTransactionExecutor transaction;
    private final Clock clock;

    /** Requires all owner capabilities; no optional no-op authorization or evidence exists. */
    public StaffProvisioningConsumptionService(StaffProvisioningIntentRepository intents,
            ResolveOrCreateExternalUserUseCase users, EnsureActiveTenantMembershipUseCase memberships,
            StaffMaterializationRepository staff, StaffProvisioningCompletion completion,
            WorkforceTransactionExecutor transaction, Clock clock) {
        this.intents = Objects.requireNonNull(intents, "intents");
        this.users = Objects.requireNonNull(users, "users");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.staff = Objects.requireNonNull(staff, "staff");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Accepts issuer/subject only from the cryptographically verified bootstrap
     * adapter. The credential is decoded canonically and never reaches storage.
     * A successful retry after rollback may proceed; committed proofs are one-time.
     */
    @Override
    public UUID consume(String secret, String verifiedIssuer, String verifiedSubject) {
        var digest = digest(secret);
        try {
            var identity = new ResolveExternalIdentityQuery(verifiedIssuer, verifiedSubject);
            return transaction.execute(() -> {
                var intent = intents.consumePending(digest, OffsetDateTime.now(clock))
                        .orElseThrow(StaffProvisioningUnavailableException::new);
                completion.authorize(intent);
                var userId = users.resolveOrCreate(identity).userId();
                if (!(memberships.ensureActive(new EstablishTenantMembershipCommand(userId, intent.tenantId()))
                        instanceof TenantMembershipEnsureResult.Operational)) {
                    throw new StaffProvisioningUnavailableException();
                }
                var staffId = staff.materialize(intent.tenantId(), userId, intent.departmentId(), intent.positionId());
                completion.complete(intent, userId, staffId);
                return staffId;
            });
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    /** Rejects alternate encodings so one issued credential has one accepted representation. */
    private byte[] digest(String secret) {
        if (secret == null || secret.length() != 43) {
            throw new StaffProvisioningUnavailableException();
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            throw new StaffProvisioningUnavailableException();
        }
        try {
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(secret)) {
                throw new StaffProvisioningUnavailableException();
            }
            return MessageDigest.getInstance("SHA-256").digest(decoded);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }
}

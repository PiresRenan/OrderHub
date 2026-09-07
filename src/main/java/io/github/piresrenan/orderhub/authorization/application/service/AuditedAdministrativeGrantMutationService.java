package io.github.piresrenan.orderhub.authorization.application.service;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditAction;
import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditEvidence;
import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditOutcome;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantAuditRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantMutationResult;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;

public final class AuditedAdministrativeGrantMutationService {

    private final AuthorizationTransactionExecutor transactions;
    private final AdministrativeGrantRepository grants;
    private final AdministrativeGrantAuditRepository audit;
    private final Supplier<UUID> auditEventIds;

    public AuditedAdministrativeGrantMutationService(
            AuthorizationTransactionExecutor transactions,
            AdministrativeGrantRepository grants,
            AdministrativeGrantAuditRepository audit,
            Supplier<UUID> auditEventIds) {

        this.transactions =
                Objects.requireNonNull(
                        transactions,
                        "transactions");

        this.grants =
                Objects.requireNonNull(
                        grants,
                        "grants");

        this.audit =
                Objects.requireNonNull(
                        audit,
                        "audit");

        this.auditEventIds =
                Objects.requireNonNull(
                        auditEventIds,
                        "auditEventIds");
    }

    public AdministrativeGrantMutationResult grant(
            UUID actorUserId,
            AdministrativeGrant grant,
            UUID correlationId) {

        return execute(
                actorUserId,
                grant,
                correlationId,
                AdministrativeGrantAuditAction.GRANT_PERMISSION);
    }

    public AdministrativeGrantMutationResult revoke(
            UUID actorUserId,
            AdministrativeGrant grant,
            UUID correlationId) {

        return execute(
                actorUserId,
                grant,
                correlationId,
                AdministrativeGrantAuditAction.REVOKE_PERMISSION);
    }

    private AdministrativeGrantMutationResult execute(
            UUID actorUserId,
            AdministrativeGrant grant,
            UUID correlationId,
            AdministrativeGrantAuditAction action) {

        Objects.requireNonNull(
                actorUserId,
                "actorUserId");

        Objects.requireNonNull(
                grant,
                "grant");

        Objects.requireNonNull(
                correlationId,
                "correlationId");

        return transactions.execute(
                () -> {

                    var mutationResult =
                            mutate(
                                    grant,
                                    action);

                    audit.append(
                            evidence(
                                    actorUserId,
                                    grant,
                                    correlationId,
                                    action,
                                    mutationResult));

                    return mutationResult;
                });
    }

    private AdministrativeGrantMutationResult mutate(
            AdministrativeGrant grant,
            AdministrativeGrantAuditAction action) {

        return switch (action) {
            case GRANT_PERMISSION ->
                    grants.grant(
                            grant);

            case REVOKE_PERMISSION ->
                    grants.revoke(
                            grant);
        };
    }

    private AdministrativeGrantAuditEvidence evidence(
            UUID actorUserId,
            AdministrativeGrant grant,
            UUID correlationId,
            AdministrativeGrantAuditAction action,
            AdministrativeGrantMutationResult result) {

        var auditEventId =
                Objects.requireNonNull(
                        auditEventIds.get(),
                        "auditEventId");

        var transition =
                transitionFor(
                        action,
                        result);

        return new AdministrativeGrantAuditEvidence(
                auditEventId,
                actorUserId,
                grant.userId(),
                grant.scope(),
                grant.permission(),
                action,
                transition.outcome(),
                correlationId,
                transition.beforeGranted(),
                transition.afterGranted());
    }

    private static GrantTransition transitionFor(
            AdministrativeGrantAuditAction action,
            AdministrativeGrantMutationResult result) {

        return switch (result) {
            case GRANTED -> {
                requireAction(
                        action,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        result);

                yield new GrantTransition(
                        AdministrativeGrantAuditOutcome.APPLIED,
                        false,
                        true);
            }

            case ALREADY_GRANTED -> {
                requireAction(
                        action,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        result);

                yield new GrantTransition(
                        AdministrativeGrantAuditOutcome.NO_CHANGE,
                        true,
                        true);
            }

            case REVOKED -> {
                requireAction(
                        action,
                        AdministrativeGrantAuditAction.REVOKE_PERMISSION,
                        result);

                yield new GrantTransition(
                        AdministrativeGrantAuditOutcome.APPLIED,
                        true,
                        false);
            }

            case ALREADY_ABSENT -> {
                requireAction(
                        action,
                        AdministrativeGrantAuditAction.REVOKE_PERMISSION,
                        result);

                yield new GrantTransition(
                        AdministrativeGrantAuditOutcome.NO_CHANGE,
                        false,
                        false);
            }
        };
    }

    private static void requireAction(
            AdministrativeGrantAuditAction actual,
            AdministrativeGrantAuditAction required,
            AdministrativeGrantMutationResult result) {

        if (actual != required) {
            throw new IllegalStateException(
                    "Administrative grant mutation result "
                            + result
                            + " is incompatible with "
                            + actual);
        }
    }

    private record GrantTransition(
            AdministrativeGrantAuditOutcome outcome,
            boolean beforeGranted,
            boolean afterGranted) {
    }
}

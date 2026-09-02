package io.github.piresrenan.orderhub.authorization.application.service;

import java.util.HashMap;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeTenantActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.service.ScopedAuthorizationEvaluator;

/**
 * Composes durable authorization policy state before evaluating one decision.
 *
 * <p>
 * Any missing, inconsistent or unavailable persisted authorization state fails
 * closed. Customer relationship authorization is deliberately excluded from
 * this STAFF RBAC path.
 * </p>
 */
public final class DurableTenantAuthorizationService
        implements AuthorizeTenantActionUseCase {

    private final RoleAssignmentRepository roleAssignmentRepository;

    private final RoleDefinitionRepository roleDefinitionRepository;

    private final UserPermissionOverrideRepository permissionOverrideRepository;

    private final ScopedAuthorizationEvaluator authorizationEvaluator;

    public DurableTenantAuthorizationService(
            RoleAssignmentRepository roleAssignmentRepository,
            RoleDefinitionRepository roleDefinitionRepository,
            UserPermissionOverrideRepository permissionOverrideRepository) {

        this(
                roleAssignmentRepository,
                roleDefinitionRepository,
                permissionOverrideRepository,
                new ScopedAuthorizationEvaluator());
    }

    DurableTenantAuthorizationService(
            RoleAssignmentRepository roleAssignmentRepository,
            RoleDefinitionRepository roleDefinitionRepository,
            UserPermissionOverrideRepository permissionOverrideRepository,
            ScopedAuthorizationEvaluator authorizationEvaluator) {

        if (roleAssignmentRepository == null) {
            throw new IllegalArgumentException(
                    "Role assignment repository is required");
        }

        if (roleDefinitionRepository == null) {
            throw new IllegalArgumentException(
                    "Role definition repository is required");
        }

        if (permissionOverrideRepository == null) {
            throw new IllegalArgumentException(
                    "Permission override repository is required");
        }

        if (authorizationEvaluator == null) {
            throw new IllegalArgumentException(
                    "Scoped authorization evaluator is required");
        }

        this.roleAssignmentRepository =
                roleAssignmentRepository;

        this.roleDefinitionRepository =
                roleDefinitionRepository;

        this.permissionOverrideRepository =
                permissionOverrideRepository;

        this.authorizationEvaluator =
                authorizationEvaluator;
    }

    @Override
    public AuthorizationDecision authorize(
            TenantAuthorizationRequest request,
            PermissionEnvelope actorEnvelope) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Authorization request is required");
        }

        if (actorEnvelope == null) {
            throw new IllegalArgumentException(
                    "Actor permission envelope is required");
        }

        /*
         * Customer resource ownership/relationship policy is a separate path.
         * Never load STAFF authorization state for a Customer request.
         */
        if (request.persona()
                != AuthorizationPersona.STAFF) {

            return AuthorizationDecision.DENY;
        }

        try {
            var assignments =
                    roleAssignmentRepository
                            .findByUserIdAndScope(
                                    request.userId(),
                                    request.scope());

            if (assignments == null
                    || assignments.stream()
                            .anyMatch(assignment ->
                                    assignment == null
                                            || !assignment.appliesTo(
                                                    request.userId(),
                                                    request.persona(),
                                                    request.scope()))) {

                return AuthorizationDecision.DENY;
            }

            var definitions =
                    new HashMap<String, RoleDefinition>();

            for (var assignment : assignments) {

                var candidate =
                        roleDefinitionRepository
                                .findByCodeAndScope(
                                        assignment.roleCode(),
                                        request.scope());

                if (candidate == null
                        || candidate.isEmpty()) {

                    return AuthorizationDecision.DENY;
                }

                var definition =
                        candidate.orElseThrow();

                if (!assignment.roleCode()
                        .equals(
                                definition.code())
                        || definition.persona()
                                != request.persona()) {

                    return AuthorizationDecision.DENY;
                }

                /*
                 * Duplicate visible definitions for one stable code are a
                 * policy inconsistency even if they happen to carry identical
                 * permissions.
                 */
                if (definitions.putIfAbsent(
                        definition.code(),
                        definition) != null) {

                    return AuthorizationDecision.DENY;
                }
            }

            var overrides =
                    permissionOverrideRepository
                            .findByUserIdAndScope(
                                    request.userId(),
                                    request.scope());

            if (overrides == null
                    || overrides.stream()
                            .anyMatch(override ->
                                    override == null
                                            || !override.appliesTo(
                                                    request.userId(),
                                                    request.scope()))) {

                return AuthorizationDecision.DENY;
            }

            return authorizationEvaluator.evaluate(
                    request,
                    assignments,
                    definitions,
                    actorEnvelope,
                    overrides);

        } catch (AuthorizationPersistenceException exception) {

            return AuthorizationDecision.DENY;
        }
    }
}

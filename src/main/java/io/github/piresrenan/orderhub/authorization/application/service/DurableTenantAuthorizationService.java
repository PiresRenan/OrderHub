package io.github.piresrenan.orderhub.authorization.application.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeTenantActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.constraint.AuthorizationConstraint;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.service.ScopedAuthorizationEvaluator;

/**
 * Composes durable authorization state and restrictive policy before evaluating
 * one Tenant-scoped STAFF decision.
 */
public final class DurableTenantAuthorizationService
        implements AuthorizeTenantActionUseCase {

    private final RoleAssignmentRepository roleAssignmentRepository;

    private final RoleDefinitionRepository roleDefinitionRepository;

    private final UserPermissionOverrideRepository permissionOverrideRepository;

    private final List<AuthorizationConstraint> constraints;

    private final ScopedAuthorizationEvaluator authorizationEvaluator;

    public DurableTenantAuthorizationService(
            RoleAssignmentRepository roleAssignmentRepository,
            RoleDefinitionRepository roleDefinitionRepository,
            UserPermissionOverrideRepository permissionOverrideRepository) {

        this(
                roleAssignmentRepository,
                roleDefinitionRepository,
                permissionOverrideRepository,
                List.of());
    }

    public DurableTenantAuthorizationService(
            RoleAssignmentRepository roleAssignmentRepository,
            RoleDefinitionRepository roleDefinitionRepository,
            UserPermissionOverrideRepository permissionOverrideRepository,
            Collection<AuthorizationConstraint> constraints) {

        this(
                roleAssignmentRepository,
                roleDefinitionRepository,
                permissionOverrideRepository,
                constraints,
                new ScopedAuthorizationEvaluator());
    }

    DurableTenantAuthorizationService(
            RoleAssignmentRepository roleAssignmentRepository,
            RoleDefinitionRepository roleDefinitionRepository,
            UserPermissionOverrideRepository permissionOverrideRepository,
            Collection<AuthorizationConstraint> constraints,
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

        if (constraints == null
                || constraints.stream()
                        .anyMatch(constraint ->
                                constraint == null)) {

            throw new IllegalArgumentException(
                    "Authorization constraints are required");
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

        this.constraints =
                List.copyOf(
                        constraints);

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
                    overrides,
                    constraints);

        } catch (AuthorizationPersistenceException exception) {

            return AuthorizationDecision.DENY;
        }
    }
}

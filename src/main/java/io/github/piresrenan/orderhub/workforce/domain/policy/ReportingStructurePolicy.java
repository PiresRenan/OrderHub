package io.github.piresrenan.orderhub.workforce.domain.policy;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.domain.model.ReportingRelationship;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;

/**
 * Enforces structural invariants for the Tenant workforce reporting graph.
 */
public final class ReportingStructurePolicy {

    public ReportingRelationship establish(
            StaffProfile supervisor,
            StaffProfile subordinate,
            Collection<ReportingRelationship> existingRelationships) {

        if (supervisor == null) {
            throw new IllegalArgumentException(
                    "Supervisor is required");
        }

        if (subordinate == null) {
            throw new IllegalArgumentException(
                    "Subordinate is required");
        }

        if (existingRelationships == null
                || existingRelationships.stream()
                        .anyMatch(
                                relationship ->
                                        relationship == null)) {

            throw new IllegalArgumentException(
                    "Existing reporting relationships are required");
        }

        if (!supervisor.tenantId()
                .equals(
                        subordinate.tenantId())) {

            throw new IllegalArgumentException(
                    "Reporting relationship cannot cross Tenant scope");
        }

        if (!supervisor.isActive()) {

            throw new IllegalArgumentException(
                    "Reporting supervisor must be ACTIVE");
        }

        var candidate =
                new ReportingRelationship(
                        supervisor.tenantId(),
                        supervisor.staffId(),
                        subordinate.staffId());

        if (createsCycle(
                candidate,
                existingRelationships)) {

            throw new IllegalArgumentException(
                    "Reporting relationship would create a cycle");
        }

        return candidate;
    }

    private boolean createsCycle(
            ReportingRelationship candidate,
            Collection<ReportingRelationship> existingRelationships) {

        var adjacency =
                new HashMap<UUID, Set<UUID>>();

        existingRelationships.stream()
                .filter(relationship ->
                        relationship.tenantId()
                                .equals(
                                        candidate.tenantId()))
                .forEach(relationship ->
                        adjacency
                                .computeIfAbsent(
                                        relationship.supervisorStaffId(),
                                        ignored ->
                                                new HashSet<>())
                                .add(
                                        relationship.subordinateStaffId()));

        var pending =
                new ArrayDeque<UUID>();

        var visited =
                new HashSet<UUID>();

        pending.add(
                candidate.subordinateStaffId());

        while (!pending.isEmpty()) {

            var current =
                    pending.removeFirst();

            if (!visited.add(
                    current)) {

                continue;
            }

            if (current.equals(
                    candidate.supervisorStaffId())) {

                return true;
            }

            adjacency
                    .getOrDefault(
                            current,
                            Set.of())
                    .forEach(
                            pending::addLast);
        }

        return false;
    }
}

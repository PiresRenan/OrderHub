package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

/**
 * Durable scoped RoleAssignment boundary.
 */
public interface RoleAssignmentRepository {

    void save(
            RoleAssignment assignment);

    List<RoleAssignment> findByUserIdAndScope(
            UUID userId,
            TenantAuthorizationScope scope);
}

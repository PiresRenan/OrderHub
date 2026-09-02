package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.Optional;

import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

/**
 * Durable read boundary for scoped RoleDefinition state.
 */
public interface RoleDefinitionRepository {

    Optional<RoleDefinition> findByCodeAndScope(
            String roleCode,
            TenantAuthorizationScope scope);
}

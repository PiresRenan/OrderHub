package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.domain.model.UserPermissionOverride;

/**
 * Durable read boundary for account-specific permission overrides.
 */
public interface UserPermissionOverrideRepository {

    List<UserPermissionOverride> findByUserIdAndScope(
            UUID userId,
            TenantAuthorizationScope scope);
}

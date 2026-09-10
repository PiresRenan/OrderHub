package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.Set;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

/** Owner-local creation used only by initial governance attribution. */
public interface ColdStartStaffAuthorizationRepository {
    boolean holdPlatformManagerGrant(UUID actorUserId);
    void lockRoleCatalog();
    void createGovernanceRole(UUID tenantId, String code, Set<PermissionCode> permissions);
}

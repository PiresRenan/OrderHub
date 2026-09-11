package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.Set;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

/** Owner-local creation used only by initial governance attribution. */
public interface ColdStartStaffAuthorizationRepository {
    /** Holds the actual Platform grant through the ceremony; absence never becomes Tenant authority. */
    boolean holdPlatformManagerGrant(UUID actorUserId);
    /** Acquires the catalog lock before role insertion to avoid shared-lock upgrade races. */
    void lockRoleCatalog();
    /** Creates only the explicit v1 governance role; all writes remain in the caller transaction. */
    void createGovernanceRole(UUID tenantId, String code, Set<PermissionCode> permissions);
}

package io.github.piresrenan.orderhub.authorization.application.port.in;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.relationship.RelationshipAuthorizationContext;

/**
 * Framework-neutral boundary for one Customer-owned resource authorization decision.
 */
public interface AuthorizeCustomerOwnedResourceActionUseCase {

    AuthorizationDecision authorize(
            TenantAuthorizationRequest request,
            RelationshipAuthorizationContext relationshipContext);
}

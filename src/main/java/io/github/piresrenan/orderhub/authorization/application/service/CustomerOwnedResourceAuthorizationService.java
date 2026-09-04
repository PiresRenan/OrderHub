package io.github.piresrenan.orderhub.authorization.application.service;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.relationship.AuthorizationRelationship;
import io.github.piresrenan.orderhub.authorization.domain.relationship.RelationshipAuthorizationContext;
import io.github.piresrenan.orderhub.authorization.domain.relationship.RequiredRelationshipPolicy;

/**
 * Composes Customer permission compatibility with exact actor, persona, Tenant
 * and resource-ownership facts.
 */
public final class CustomerOwnedResourceAuthorizationService
        implements AuthorizeCustomerOwnedResourceActionUseCase {

    private static final RequiredRelationshipPolicy CUSTOMER_OWNER_POLICY =
            new RequiredRelationshipPolicy(
                    AuthorizationPersona.CUSTOMER,
                    AuthorizationRelationship.RESOURCE_OWNER);

    @Override
    public AuthorizationDecision authorize(
            TenantAuthorizationRequest request,
            RelationshipAuthorizationContext relationshipContext) {

        if (request.persona()
                != AuthorizationPersona.CUSTOMER) {

            return AuthorizationDecision.DENY;
        }

        if (!request.permission()
                .supports(
                        request.persona())) {

            return AuthorizationDecision.DENY;
        }

        if (!request.userId()
                .equals(
                        relationshipContext.actorUserId())) {

            return AuthorizationDecision.DENY;
        }

        if (request.persona()
                != relationshipContext.persona()) {

            return AuthorizationDecision.DENY;
        }

        if (!request.scope()
                .equals(
                        relationshipContext.scope())) {

            return AuthorizationDecision.DENY;
        }

        return CUSTOMER_OWNER_POLICY.evaluate(
                relationshipContext);
    }
}

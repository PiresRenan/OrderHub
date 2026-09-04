package io.github.piresrenan.orderhub.authorization.application.service;

import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision.ALLOW;
import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision.DENY;
import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona.CUSTOMER;
import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona.STAFF;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.CUSTOMER_ORDERS_CREATE;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.CUSTOMER_ORDERS_VIEW;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.ORDERS_VIEW;
import static io.github.piresrenan.orderhub.authorization.domain.relationship.AuthorizationRelationship.RESOURCE_OWNER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.domain.relationship.AuthorizationRelationship;
import io.github.piresrenan.orderhub.authorization.domain.relationship.RelationshipAuthorizationContext;

class CustomerOwnedResourceAuthorizationServiceTest {

    private final AuthorizeCustomerOwnedResourceActionUseCase authorization =
            new CustomerOwnedResourceAuthorizationService();

    @Test
    void allowsCustomerOrderViewWhenActorScopePermissionAndOwnershipMatch() {

        var fixture =
                matchingFixture(
                        CUSTOMER,
                        CUSTOMER_ORDERS_VIEW,
                        true);

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        fixture.relationshipContext()))
                .isEqualTo(ALLOW);
    }

    @Test
    void allowsCustomerOrderCreateWhenActorScopePermissionAndOwnershipMatch() {

        var fixture =
                matchingFixture(
                        CUSTOMER,
                        CUSTOMER_ORDERS_CREATE,
                        true);

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        fixture.relationshipContext()))
                .isEqualTo(ALLOW);
    }

    @Test
    void deniesWhenResourceOwnershipIsMissing() {

        var fixture =
                matchingFixture(
                        CUSTOMER,
                        CUSTOMER_ORDERS_VIEW,
                        false);

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        fixture.relationshipContext()))
                .isEqualTo(DENY);
    }

    @Test
    void deniesCustomerPermissionWhenActingAsStaff() {

        var fixture =
                matchingFixture(
                        STAFF,
                        CUSTOMER_ORDERS_VIEW,
                        true);

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        fixture.relationshipContext()))
                .isEqualTo(DENY);
    }

    @Test
    void deniesStaffPermissionWhenActingAsCustomer() {

        var fixture =
                matchingFixture(
                        CUSTOMER,
                        ORDERS_VIEW,
                        true);

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        fixture.relationshipContext()))
                .isEqualTo(DENY);
    }

    @Test
    void deniesWhenRelationshipActorDoesNotMatchAuthorizationActor() {

        var fixture =
                matchingFixture(
                        CUSTOMER,
                        CUSTOMER_ORDERS_VIEW,
                        true);

        var mismatchedContext =
                new RelationshipAuthorizationContext(
                        UUID.randomUUID(),
                        CUSTOMER,
                        fixture.request().scope(),
                        Set.of(RESOURCE_OWNER));

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        mismatchedContext))
                .isEqualTo(DENY);
    }

    @Test
    void deniesWhenRelationshipTenantDoesNotMatchAuthorizationTenant() {

        var fixture =
                matchingFixture(
                        CUSTOMER,
                        CUSTOMER_ORDERS_VIEW,
                        true);

        var mismatchedContext =
                new RelationshipAuthorizationContext(
                        fixture.request().userId(),
                        CUSTOMER,
                        new TenantAuthorizationScope(
                                UUID.randomUUID()),
                        Set.of(RESOURCE_OWNER));

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        mismatchedContext))
                .isEqualTo(DENY);
    }

    @Test
    void deniesWhenRelationshipPersonaDoesNotMatchAuthorizationPersona() {

        var fixture =
                matchingFixture(
                        CUSTOMER,
                        CUSTOMER_ORDERS_VIEW,
                        true);

        var mismatchedContext =
                new RelationshipAuthorizationContext(
                        fixture.request().userId(),
                        STAFF,
                        fixture.request().scope(),
                        Set.of(RESOURCE_OWNER));

        assertThat(
                authorization.authorize(
                        fixture.request(),
                        mismatchedContext))
                .isEqualTo(DENY);
    }

    private static Fixture matchingFixture(
            AuthorizationPersona persona,
            PermissionCode permission,
            boolean resourceOwner) {

        var userId =
                UUID.randomUUID();

        var scope =
                new TenantAuthorizationScope(
                        UUID.randomUUID());

        var request =
                new TenantAuthorizationRequest(
                        userId,
                        persona,
                        scope,
                        permission);

        Set<AuthorizationRelationship> relationships =
                resourceOwner
                        ? Set.of(RESOURCE_OWNER)
                        : Set.of();

        var relationshipContext =
                new RelationshipAuthorizationContext(
                        userId,
                        persona,
                        scope,
                        relationships);

        return new Fixture(
                request,
                relationshipContext);
    }

    private record Fixture(
            TenantAuthorizationRequest request,
            RelationshipAuthorizationContext relationshipContext) {
    }
}

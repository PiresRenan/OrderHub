package io.github.piresrenan.orderhub.orders.application.service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.domain.relationship.AuthorizationRelationship;
import io.github.piresrenan.orderhub.authorization.domain.relationship.RelationshipAuthorizationContext;
import io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderUnavailableException;
import io.github.piresrenan.orderhub.orders.application.port.in.ViewCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;

/**
 * Loads one Order inside the trusted Tenant and proves the authenticated
 * Customer owns that authoritative Order before returning it.
 */
public final class ViewCustomerOrderService
        implements ViewCustomerOrderUseCase {

    private final OrderRepository orders;

    private final ResolveCustomerAccountBindingUseCase bindings;

    private final AuthorizeCustomerOwnedResourceActionUseCase authorization;

    public ViewCustomerOrderService(
            OrderRepository orders,
            ResolveCustomerAccountBindingUseCase bindings,
            AuthorizeCustomerOwnedResourceActionUseCase authorization) {

        this.orders =
                Objects.requireNonNull(
                        orders,
                        "orders");

        this.bindings =
                Objects.requireNonNull(
                        bindings,
                        "bindings");

        this.authorization =
                Objects.requireNonNull(
                        authorization,
                        "authorization");
    }

    @Override
    public Order view(
            UUID actorUserId,
            UUID tenantId,
            UUID orderId) {

        Objects.requireNonNull(
                actorUserId,
                "actorUserId");

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                orderId,
                "orderId");

        var order =
                orders.findById(
                                tenantId,
                                orderId)
                        .orElseThrow(
                                CustomerOrderUnavailableException::new);

        var binding =
                bindings.resolve(
                        tenantId,
                        order.customerId(),
                        actorUserId);

        Set<AuthorizationRelationship> relationships =
                binding
                        == CustomerAccountBindingResolution.BOUND
                                ? Set.of(
                                        AuthorizationRelationship.RESOURCE_OWNER)
                                : Set.of();

        var scope =
                new TenantAuthorizationScope(
                        tenantId);

        var request =
                new TenantAuthorizationRequest(
                        actorUserId,
                        AuthorizationPersona.CUSTOMER,
                        scope,
                        PermissionCode.CUSTOMER_ORDERS_VIEW);

        var relationshipContext =
                new RelationshipAuthorizationContext(
                        actorUserId,
                        AuthorizationPersona.CUSTOMER,
                        scope,
                        relationships);

        var decision =
                authorization.authorize(
                        request,
                        relationshipContext);

        if (decision != AuthorizationDecision.ALLOW) {
            throw new CustomerOrderUnavailableException();
        }

        return order;
    }
}

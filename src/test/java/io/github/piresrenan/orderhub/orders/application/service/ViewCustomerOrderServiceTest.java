package io.github.piresrenan.orderhub.orders.application.service;

import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision.ALLOW;
import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision.DENY;
import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona.CUSTOMER;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.CUSTOMER_ORDERS_VIEW;
import static io.github.piresrenan.orderhub.authorization.domain.relationship.AuthorizationRelationship.RESOURCE_OWNER;
import static io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution.BOUND;
import static io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution.NOT_BOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.relationship.RelationshipAuthorizationContext;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderUnavailableException;
import io.github.piresrenan.orderhub.orders.application.port.in.ViewCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

class ViewCustomerOrderServiceTest {

    @Test
    void ownedOrderUsesAuthoritativeOrderCustomerAndReturnsItWhenAllowed() {

        var actorUserId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        var authoritativeCustomerId =
                UUID.randomUUID();

        var order =
                order(
                        orderId,
                        tenantId,
                        authoritativeCustomerId);

        var repositoryTenant =
                new AtomicReference<UUID>();

        var repositoryOrder =
                new AtomicReference<UUID>();

        OrderRepository orders =
                repositoryReturning(
                        Optional.of(order),
                        repositoryTenant,
                        repositoryOrder);

        var boundTenant =
                new AtomicReference<UUID>();

        var boundCustomer =
                new AtomicReference<UUID>();

        var boundUser =
                new AtomicReference<UUID>();

        ResolveCustomerAccountBindingUseCase bindings =
                (resolvedTenantId, customerId, userId) -> {

                    boundTenant.set(
                            resolvedTenantId);

                    boundCustomer.set(
                            customerId);

                    boundUser.set(
                            userId);

                    return BOUND;
                };

        var authorizationRequest =
                new AtomicReference<TenantAuthorizationRequest>();

        var relationshipContext =
                new AtomicReference<RelationshipAuthorizationContext>();

        AuthorizeCustomerOwnedResourceActionUseCase authorization =
                (request, context) -> {

                    authorizationRequest.set(
                            request);

                    relationshipContext.set(
                            context);

                    return ALLOW;
                };

        ViewCustomerOrderUseCase customerView =
                new ViewCustomerOrderService(
                        orders,
                        bindings,
                        authorization);

        var actual =
                customerView.view(
                        actorUserId,
                        tenantId,
                        orderId);

        assertThat(actual)
                .isSameAs(order);

        assertThat(repositoryTenant.get())
                .isEqualTo(tenantId);

        assertThat(repositoryOrder.get())
                .isEqualTo(orderId);

        assertThat(boundTenant.get())
                .isEqualTo(tenantId);

        assertThat(boundCustomer.get())
                .isEqualTo(authoritativeCustomerId);

        assertThat(boundUser.get())
                .isEqualTo(actorUserId);

        assertThat(authorizationRequest.get().userId())
                .isEqualTo(actorUserId);

        assertThat(authorizationRequest.get().persona())
                .isEqualTo(CUSTOMER);

        assertThat(authorizationRequest.get().scope().tenantId())
                .isEqualTo(tenantId);

        assertThat(authorizationRequest.get().permission())
                .isEqualTo(CUSTOMER_ORDERS_VIEW);

        assertThat(relationshipContext.get().actorUserId())
                .isEqualTo(actorUserId);

        assertThat(relationshipContext.get().persona())
                .isEqualTo(CUSTOMER);

        assertThat(relationshipContext.get().scope())
                .isEqualTo(
                        authorizationRequest
                                .get()
                                .scope());

        assertThat(relationshipContext.get().relationships())
                .containsExactly(
                        RESOURCE_OWNER);
    }

    @Test
    void missingOrderIsUnavailableWithoutResolvingOwnershipOrAuthorization() {

        var actorUserId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        OrderRepository orders =
                repositoryReturning(
                        Optional.empty(),
                        new AtomicReference<>(),
                        new AtomicReference<>());

        var bindingCalls =
                new AtomicInteger();

        ResolveCustomerAccountBindingUseCase bindings =
                (resolvedTenantId, customerId, userId) -> {

                    bindingCalls.incrementAndGet();

                    return BOUND;
                };

        var authorizationCalls =
                new AtomicInteger();

        AuthorizeCustomerOwnedResourceActionUseCase authorization =
                (request, context) -> {

                    authorizationCalls.incrementAndGet();

                    return ALLOW;
                };

        ViewCustomerOrderUseCase customerView =
                new ViewCustomerOrderService(
                        orders,
                        bindings,
                        authorization);

        assertThatThrownBy(() ->
                customerView.view(
                        actorUserId,
                        tenantId,
                        orderId))
                .isInstanceOf(
                        CustomerOrderUnavailableException.class);

        assertThat(bindingCalls.get())
                .isZero();

        assertThat(authorizationCalls.get())
                .isZero();
    }

    @Test
    void unboundOrderSuppliesNoOwnershipFactAndRemainsUnavailable() {

        var actorUserId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        var order =
                order(
                        orderId,
                        tenantId,
                        UUID.randomUUID());

        OrderRepository orders =
                repositoryReturning(
                        Optional.of(order),
                        new AtomicReference<>(),
                        new AtomicReference<>());

        ResolveCustomerAccountBindingUseCase bindings =
                (resolvedTenantId, customerId, userId) ->
                        NOT_BOUND;

        var authorizationCalls =
                new AtomicInteger();

        var relationshipContext =
                new AtomicReference<RelationshipAuthorizationContext>();

        AuthorizeCustomerOwnedResourceActionUseCase authorization =
                (request, context) -> {

                    authorizationCalls.incrementAndGet();

                    relationshipContext.set(
                            context);

                    return DENY;
                };

        ViewCustomerOrderUseCase customerView =
                new ViewCustomerOrderService(
                        orders,
                        bindings,
                        authorization);

        assertThatThrownBy(() ->
                customerView.view(
                        actorUserId,
                        tenantId,
                        orderId))
                .isInstanceOf(
                        CustomerOrderUnavailableException.class);

        assertThat(authorizationCalls.get())
                .isEqualTo(1);

        assertThat(relationshipContext.get().relationships())
                .isEmpty();
    }

    @Test
    void boundOrderStillRemainsUnavailableWhenAuthorizationDenies() {

        var actorUserId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        var order =
                order(
                        orderId,
                        tenantId,
                        UUID.randomUUID());

        OrderRepository orders =
                repositoryReturning(
                        Optional.of(order),
                        new AtomicReference<>(),
                        new AtomicReference<>());

        ResolveCustomerAccountBindingUseCase bindings =
                (resolvedTenantId, customerId, userId) ->
                        BOUND;

        AuthorizeCustomerOwnedResourceActionUseCase authorization =
                (request, context) ->
                        DENY;

        ViewCustomerOrderUseCase customerView =
                new ViewCustomerOrderService(
                        orders,
                        bindings,
                        authorization);

        assertThatThrownBy(() ->
                customerView.view(
                        actorUserId,
                        tenantId,
                        orderId))
                .isInstanceOf(
                        CustomerOrderUnavailableException.class);
    }

    private static OrderRepository repositoryReturning(
            Optional<Order> result,
            AtomicReference<UUID> requestedTenant,
            AtomicReference<UUID> requestedOrder) {

        return new OrderRepository() {

            @Override
            public Order save(
                    Order order) {

                throw new AssertionError(
                        "Own-Order read must never write an Order");
            }

            @Override
            public Optional<Order> findById(
                    UUID tenantId,
                    UUID orderId) {

                requestedTenant.set(
                        tenantId);

                requestedOrder.set(
                        orderId);

                return result;
            }
        };
    }

    private static Order order(
            UUID orderId,
            UUID tenantId,
            UUID customerId) {

        return Order.create(
                orderId,
                tenantId,
                customerId,
                List.of(
                        new OrderItem(
                                UUID.randomUUID(),
                                1)));
    }
}

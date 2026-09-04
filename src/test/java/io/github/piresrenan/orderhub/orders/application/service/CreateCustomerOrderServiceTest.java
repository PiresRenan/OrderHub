package io.github.piresrenan.orderhub.orders.application.service;

import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision.ALLOW;
import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision.DENY;
import static io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona.CUSTOMER;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.CUSTOMER_ORDERS_CREATE;
import static io.github.piresrenan.orderhub.authorization.domain.relationship.AuthorizationRelationship.RESOURCE_OWNER;
import static io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution.BOUND;
import static io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution.NOT_BOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.relationship.RelationshipAuthorizationContext;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderAccessDeniedException;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

class CreateCustomerOrderServiceTest {

    @Test
    void boundCustomerWithAllowedPolicyDelegatesTheExactExistingCreateCommand() {

        var actorUserId = UUID.randomUUID();
        var command = command();
        var expectedResult = resultFor(command);

        var boundTenant = new AtomicReference<UUID>();
        var boundCustomer = new AtomicReference<UUID>();
        var boundUser = new AtomicReference<UUID>();

        ResolveCustomerAccountBindingUseCase bindings =
                (tenantId, customerId, userId) -> {
                    boundTenant.set(tenantId);
                    boundCustomer.set(customerId);
                    boundUser.set(userId);
                    return BOUND;
                };

        var authorizationRequest =
                new AtomicReference<TenantAuthorizationRequest>();

        var relationshipContext =
                new AtomicReference<RelationshipAuthorizationContext>();

        var authorizationCalls =
                new AtomicInteger();

        AuthorizeCustomerOwnedResourceActionUseCase authorization =
                (request, context) -> {
                    authorizationCalls.incrementAndGet();
                    authorizationRequest.set(request);
                    relationshipContext.set(context);
                    return ALLOW;
                };

        var delegatedCommand =
                new AtomicReference<CreateOrderCommand>();

        CreateOrderUseCase existingCreate =
                delegated -> {
                    delegatedCommand.set(delegated);
                    return expectedResult;
                };

        CreateCustomerOrderUseCase customerCreate =
                new CreateCustomerOrderService(
                        bindings,
                        authorization,
                        existingCreate);

        var actual =
                customerCreate.create(
                        actorUserId,
                        command);

        assertThat(actual)
                .isSameAs(expectedResult);

        assertThat(delegatedCommand.get())
                .isSameAs(command);

        assertThat(boundTenant.get())
                .isEqualTo(command.tenantId());

        assertThat(boundCustomer.get())
                .isEqualTo(command.customerId());

        assertThat(boundUser.get())
                .isEqualTo(actorUserId);

        assertThat(authorizationCalls.get())
                .isEqualTo(1);

        assertThat(authorizationRequest.get().userId())
                .isEqualTo(actorUserId);

        assertThat(authorizationRequest.get().persona())
                .isEqualTo(CUSTOMER);

        assertThat(authorizationRequest.get().scope().tenantId())
                .isEqualTo(command.tenantId());

        assertThat(authorizationRequest.get().permission())
                .isEqualTo(CUSTOMER_ORDERS_CREATE);

        assertThat(relationshipContext.get().actorUserId())
                .isEqualTo(actorUserId);

        assertThat(relationshipContext.get().persona())
                .isEqualTo(CUSTOMER);

        assertThat(relationshipContext.get().scope())
                .isEqualTo(authorizationRequest.get().scope());

        assertThat(relationshipContext.get().relationships())
                .containsExactly(RESOURCE_OWNER);
    }

    @Test
    void unboundCustomerSuppliesNoOwnershipFactAndNeverCreatesAnOrder() {

        var actorUserId = UUID.randomUUID();
        var command = command();

        ResolveCustomerAccountBindingUseCase bindings =
                (tenantId, customerId, userId) ->
                        NOT_BOUND;

        var relationshipContext =
                new AtomicReference<RelationshipAuthorizationContext>();

        var authorizationCalls =
                new AtomicInteger();

        AuthorizeCustomerOwnedResourceActionUseCase authorization =
                (request, context) -> {
                    authorizationCalls.incrementAndGet();
                    relationshipContext.set(context);
                    return DENY;
                };

        var createCalls =
                new AtomicInteger();

        CreateOrderUseCase existingCreate =
                delegated -> {
                    createCalls.incrementAndGet();
                    throw new AssertionError(
                            "Existing create use case must not run for an unbound Customer");
                };

        CreateCustomerOrderUseCase customerCreate =
                new CreateCustomerOrderService(
                        bindings,
                        authorization,
                        existingCreate);

        assertThatThrownBy(() ->
                customerCreate.create(
                        actorUserId,
                        command))
                .isInstanceOf(
                        CustomerOrderAccessDeniedException.class);

        assertThat(authorizationCalls.get())
                .isEqualTo(1);

        assertThat(relationshipContext.get().relationships())
                .isEmpty();

        assertThat(createCalls.get())
                .isZero();
    }

    @Test
    void boundCustomerStillFailsClosedWhenAuthorizationDenies() {

        var actorUserId = UUID.randomUUID();
        var command = command();

        ResolveCustomerAccountBindingUseCase bindings =
                (tenantId, customerId, userId) ->
                        BOUND;

        AuthorizeCustomerOwnedResourceActionUseCase authorization =
                (request, context) ->
                        DENY;

        var createCalls =
                new AtomicInteger();

        CreateOrderUseCase existingCreate =
                delegated -> {
                    createCalls.incrementAndGet();
                    throw new AssertionError(
                            "Existing create use case must not run after authorization denial");
                };

        CreateCustomerOrderUseCase customerCreate =
                new CreateCustomerOrderService(
                        bindings,
                        authorization,
                        existingCreate);

        assertThatThrownBy(() ->
                customerCreate.create(
                        actorUserId,
                        command))
                .isInstanceOf(
                        CustomerOrderAccessDeniedException.class);

        assertThat(createCalls.get())
                .isZero();
    }

    private static CreateOrderCommand command() {

        return new CreateOrderCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(
                        new CreateOrderCommand.Item(
                                UUID.randomUUID(),
                                2)),
                CreateOrderIdempotencyKeyDigest.of(
                        new byte[32]));
    }

    private static CreateOrderResult resultFor(
            CreateOrderCommand command) {

        var firstItem =
                command.items().getFirst();

        var order =
                Order.create(
                        UUID.randomUUID(),
                        command.tenantId(),
                        command.customerId(),
                        List.of(
                                new OrderItem(
                                        firstItem.variantId(),
                                        firstItem.quantity())));

        return new CreateOrderResult(
                order,
                CreateOrderAllocationOutcome.FULLY_ALLOCATED);
    }
}

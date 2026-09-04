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
import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderAccessDeniedException;

/**
 * Proves one exact Customer account binding and composes the resulting
 * relationship fact with Customer authorization before delegating to the
 * established create-Order pipeline.
 */
public final class CreateCustomerOrderService
        implements CreateCustomerOrderUseCase {

    private final ResolveCustomerAccountBindingUseCase bindings;

    private final AuthorizeCustomerOwnedResourceActionUseCase authorization;

    private final CreateOrderUseCase existingCreate;

    public CreateCustomerOrderService(
            ResolveCustomerAccountBindingUseCase bindings,
            AuthorizeCustomerOwnedResourceActionUseCase authorization,
            CreateOrderUseCase existingCreate) {

        this.bindings =
                Objects.requireNonNull(
                        bindings,
                        "bindings");

        this.authorization =
                Objects.requireNonNull(
                        authorization,
                        "authorization");

        this.existingCreate =
                Objects.requireNonNull(
                        existingCreate,
                        "existingCreate");
    }

    @Override
    public CreateOrderResult create(
            UUID actorUserId,
            CreateOrderCommand command) {

        Objects.requireNonNull(
                actorUserId,
                "actorUserId");

        Objects.requireNonNull(
                command,
                "command");

        var scope =
                new TenantAuthorizationScope(
                        command.tenantId());

        var binding =
                bindings.resolve(
                        command.tenantId(),
                        command.customerId(),
                        actorUserId);

        Set<AuthorizationRelationship> relationships =
                binding
                        == CustomerAccountBindingResolution.BOUND
                                ? Set.of(
                                        AuthorizationRelationship.RESOURCE_OWNER)
                                : Set.of();

        var request =
                new TenantAuthorizationRequest(
                        actorUserId,
                        AuthorizationPersona.CUSTOMER,
                        scope,
                        PermissionCode.CUSTOMER_ORDERS_CREATE);

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
            throw new CustomerOrderAccessDeniedException();
        }

        return existingCreate.create(
                command);
    }
}

package io.github.piresrenan.orderhub.customers.application.service;

import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution;
import io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingTechnicalException;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingPersistenceException;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingRepository;

/**
 * Resolves whether one exact durable Customer account relationship exists.
 */
public final class ResolveCustomerAccountBindingService
        implements ResolveCustomerAccountBindingUseCase {

    private final CustomerAccountBindingRepository repository;

    public ResolveCustomerAccountBindingService(
            CustomerAccountBindingRepository repository) {

        this.repository =
                repository;
    }

    @Override
    public CustomerAccountBindingResolution resolve(
            UUID tenantId,
            UUID customerId,
            UUID userId) {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                customerId,
                "customerId");

        Objects.requireNonNull(
                userId,
                "userId");

        try {
            if (
                repository.existsExact(
                        tenantId,
                        customerId,
                        userId)
            ) {

                return CustomerAccountBindingResolution.BOUND;
            }

            return CustomerAccountBindingResolution.NOT_BOUND;

        } catch (CustomerAccountBindingPersistenceException exception) {

            throw new CustomerAccountBindingTechnicalException(
                    exception);
        }
    }
}

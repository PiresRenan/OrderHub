package io.github.piresrenan.orderhub.customers.application.service;

import static io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution.BOUND;
import static io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingResolution.NOT_BOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingRepository;

class ResolveCustomerAccountBindingServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID OTHER_TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000002");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID OTHER_CUSTOMER_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000002");

    private static final UUID USER_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID OTHER_USER_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000002");

    @Test
    void resolvesBoundOnlyForExactTenantCustomerAndUserRelationship() {

        CustomerAccountBindingRepository repository =
                new ExactBindingRepository(
                        TENANT_ID,
                        CUSTOMER_ID,
                        USER_ID);

        ResolveCustomerAccountBindingUseCase useCase =
                new ResolveCustomerAccountBindingService(
                        repository);

        assertThat(useCase.resolve(
                TENANT_ID,
                CUSTOMER_ID,
                USER_ID))
                .isEqualTo(
                        BOUND);

        assertThat(useCase.resolve(
                OTHER_TENANT_ID,
                CUSTOMER_ID,
                USER_ID))
                .as("Tenant mismatch must not inherit another Tenant relationship")
                .isEqualTo(
                        NOT_BOUND);

        assertThat(useCase.resolve(
                TENANT_ID,
                OTHER_CUSTOMER_ID,
                USER_ID))
                .as("User binding to one Customer grants no authority over another Customer")
                .isEqualTo(
                        NOT_BOUND);

        assertThat(useCase.resolve(
                TENANT_ID,
                CUSTOMER_ID,
                OTHER_USER_ID))
                .as("Customer binding to one User grants no authority to another User")
                .isEqualTo(
                        NOT_BOUND);
    }

    @Test
    void rejectsMissingTenantBeforeRepositoryResolution() {

        var service =
                new ResolveCustomerAccountBindingService(
                        repositoryThatMustNotBeCalled());

        assertThatThrownBy(() ->
                service.resolve(
                        null,
                        CUSTOMER_ID,
                        USER_ID))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "tenantId");
    }

    @Test
    void rejectsMissingCustomerBeforeRepositoryResolution() {

        var service =
                new ResolveCustomerAccountBindingService(
                        repositoryThatMustNotBeCalled());

        assertThatThrownBy(() ->
                service.resolve(
                        TENANT_ID,
                        null,
                        USER_ID))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "customerId");
    }

    @Test
    void rejectsMissingUserBeforeRepositoryResolution() {

        var service =
                new ResolveCustomerAccountBindingService(
                        repositoryThatMustNotBeCalled());

        assertThatThrownBy(() ->
                service.resolve(
                        TENANT_ID,
                        CUSTOMER_ID,
                        null))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "userId");
    }

    private static CustomerAccountBindingRepository repositoryThatMustNotBeCalled() {

        return (
                tenantId,
                customerId,
                userId) -> {

            throw new AssertionError(
                    "Repository must not be called for an incomplete binding identity.");
        };
    }

    private record ExactBindingRepository(
            UUID boundTenantId,
            UUID boundCustomerId,
            UUID boundUserId)
            implements CustomerAccountBindingRepository {

        @Override
        public boolean existsExact(
                UUID tenantId,
                UUID customerId,
                UUID userId) {

            return boundTenantId.equals(tenantId)
                    && boundCustomerId.equals(customerId)
                    && boundUserId.equals(userId);
        }
    }
}

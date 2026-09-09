package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityCommand;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.CreateUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.CreatedUserIdentity;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityUserProvisioningCoordinator;

class ResolveOrCreateExternalUserServiceTest {

    private static final String SERVICE_CLASS =
            "io.github.piresrenan.orderhub.users.application.service."
                    + "ResolveOrCreateExternalUserService";

    private static final String MISSING_SERVICE_MESSAGE =
            "External identity resolve-or-create application service is missing";

    private static final String ISSUER =
            "https://identity.example.test/tenant";

    private static final String SUBJECT =
            "external-subject-001";

    private static final UUID EXISTING_USER_ID =
            UUID.fromString(
                    "61000000-0000-4000-8000-000000000001");

    private static final UUID CREATED_USER_ID =
            UUID.fromString(
                    "61000000-0000-4000-8000-000000000002");

    @Test
    void existingBindingReturnsExistingUserWithoutCreatingOrBinding() {

        var coordinator =
                new RecordingCoordinator();

        var createCalls =
                new AtomicInteger();

        var bindCalls =
                new AtomicInteger();

        ResolveExternalIdentityUseCase resolver =
                query -> {

                    assertInsideCoordinator(
                            coordinator);

                    assertThat(query.issuer())
                            .isEqualTo(
                                    ISSUER);

                    assertThat(query.subject())
                            .isEqualTo(
                                    SUBJECT);

                    return Optional.of(
                            new ResolvedUserIdentity(
                                    EXISTING_USER_ID));
                };

        CreateUserUseCase creator =
                () -> {

                    assertInsideCoordinator(
                            coordinator);

                    createCalls.incrementAndGet();

                    return new CreatedUserIdentity(
                            CREATED_USER_ID);
                };

        BindExternalIdentityUseCase binder =
                command -> {

                    assertInsideCoordinator(
                            coordinator);

                    bindCalls.incrementAndGet();
                };

        var result =
                service(
                        coordinator,
                        resolver,
                        creator,
                        binder)
                        .resolveOrCreate(
                                query());

        assertThat(result.userId())
                .isEqualTo(
                        EXISTING_USER_ID);

        assertThat(createCalls.get())
                .isZero();

        assertThat(bindCalls.get())
                .isZero();

        assertCoordinatorInvocation(
                coordinator);
    }

    @Test
    void absentBindingCreatesOneUserAndBindsTheExactExternalIdentity() {

        var coordinator =
                new RecordingCoordinator();

        var createCalls =
                new AtomicInteger();

        var bindCalls =
                new AtomicInteger();

        var capturedBinding =
                new BindExternalIdentityCommand[1];

        ResolveExternalIdentityUseCase resolver =
                query -> {

                    assertInsideCoordinator(
                            coordinator);

                    return Optional.empty();
                };

        CreateUserUseCase creator =
                () -> {

                    assertInsideCoordinator(
                            coordinator);

                    createCalls.incrementAndGet();

                    return new CreatedUserIdentity(
                            CREATED_USER_ID);
                };

        BindExternalIdentityUseCase binder =
                command -> {

                    assertInsideCoordinator(
                            coordinator);

                    bindCalls.incrementAndGet();

                    capturedBinding[0] =
                            command;
                };

        var result =
                service(
                        coordinator,
                        resolver,
                        creator,
                        binder)
                        .resolveOrCreate(
                                query());

        assertThat(result.userId())
                .isEqualTo(
                        CREATED_USER_ID);

        assertThat(createCalls.get())
                .isEqualTo(
                        1);

        assertThat(bindCalls.get())
                .isEqualTo(
                        1);

        assertThat(capturedBinding[0])
                .isNotNull();

        assertThat(capturedBinding[0].issuer())
                .isEqualTo(
                        ISSUER);

        assertThat(capturedBinding[0].subject())
                .isEqualTo(
                        SUBJECT);

        assertThat(capturedBinding[0].userId())
                .isEqualTo(
                        CREATED_USER_ID);

        assertCoordinatorInvocation(
                coordinator);
    }

    @Test
    void userCreationFailurePropagatesAndBindingIsNotAttempted() {

        var coordinator =
                new RecordingCoordinator();

        var failure =
                new IllegalStateException(
                        "synthetic user creation failure");

        var bindCalls =
                new AtomicInteger();

        ResolveExternalIdentityUseCase resolver =
                query -> {

                    assertInsideCoordinator(
                            coordinator);

                    return Optional.empty();
                };

        CreateUserUseCase creator =
                () -> {

                    assertInsideCoordinator(
                            coordinator);

                    throw failure;
                };

        BindExternalIdentityUseCase binder =
                command -> {

                    assertInsideCoordinator(
                            coordinator);

                    bindCalls.incrementAndGet();
                };

        assertThatThrownBy(
                () -> service(
                        coordinator,
                        resolver,
                        creator,
                        binder)
                        .resolveOrCreate(
                                query()))
                .isSameAs(
                        failure);

        assertThat(bindCalls.get())
                .isZero();

        assertCoordinatorInvocation(
                coordinator);
    }

    @Test
    void bindingFailurePropagatesFromTheAtomicCoordinationScope() {

        var coordinator =
                new RecordingCoordinator();

        var failure =
                new IllegalStateException(
                        "synthetic binding failure");

        ResolveExternalIdentityUseCase resolver =
                query -> {

                    assertInsideCoordinator(
                            coordinator);

                    return Optional.empty();
                };

        CreateUserUseCase creator =
                () -> {

                    assertInsideCoordinator(
                            coordinator);

                    return new CreatedUserIdentity(
                            CREATED_USER_ID);
                };

        BindExternalIdentityUseCase binder =
                command -> {

                    assertInsideCoordinator(
                            coordinator);

                    throw failure;
                };

        assertThatThrownBy(
                () -> service(
                        coordinator,
                        resolver,
                        creator,
                        binder)
                        .resolveOrCreate(
                                query()))
                .isSameAs(
                        failure);

        assertCoordinatorInvocation(
                coordinator);
    }

    private ResolveOrCreateExternalUserUseCase service(
            ExternalIdentityUserProvisioningCoordinator coordinator,
            ResolveExternalIdentityUseCase resolver,
            CreateUserUseCase creator,
            BindExternalIdentityUseCase binder) {

        try {

            var serviceType =
                    Class.forName(
                            SERVICE_CLASS);

            var constructor =
                    serviceType.getConstructor(
                            ExternalIdentityUserProvisioningCoordinator.class,
                            ResolveExternalIdentityUseCase.class,
                            CreateUserUseCase.class,
                            BindExternalIdentityUseCase.class);

            return (ResolveOrCreateExternalUserUseCase)
                    constructor.newInstance(
                            coordinator,
                            resolver,
                            creator,
                            binder);

        } catch (ClassNotFoundException exception) {

            throw new AssertionError(
                    MISSING_SERVICE_MESSAGE,
                    exception);

        } catch (
                InstantiationException
                        | IllegalAccessException
                        | NoSuchMethodException exception) {

            throw new AssertionError(
                    "External identity resolve-or-create service cannot be constructed",
                    exception);

        } catch (InvocationTargetException exception) {

            var cause =
                    exception.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new AssertionError(
                    "External identity resolve-or-create constructor failed",
                    cause);
        }
    }

    private ResolveExternalIdentityQuery query() {

        return new ResolveExternalIdentityQuery(
                ISSUER,
                SUBJECT);
    }

    private static void assertInsideCoordinator(
            RecordingCoordinator coordinator) {

        assertThat(coordinator.inScope())
                .as(
                        "all resolve/create/bind work must execute inside"
                                + " the provisioning coordination scope")
                .isTrue();
    }

    private static void assertCoordinatorInvocation(
            RecordingCoordinator coordinator) {

        assertThat(coordinator.executionCount())
                .isEqualTo(
                        1);

        assertThat(coordinator.issuer())
                .isEqualTo(
                        ISSUER);

        assertThat(coordinator.subject())
                .isEqualTo(
                        SUBJECT);

        assertThat(coordinator.inScope())
                .isFalse();
    }

    private static final class RecordingCoordinator
            implements ExternalIdentityUserProvisioningCoordinator {

        private final AtomicInteger executions =
                new AtomicInteger();

        private final AtomicBoolean inScope =
                new AtomicBoolean();

        private String issuer;

        private String subject;

        @Override
        public <T> T executeSerialized(
                String issuer,
                String subject,
                java.util.function.Supplier<T> work) {

            this.issuer =
                    issuer;

            this.subject =
                    subject;

            executions.incrementAndGet();

            if (!inScope.compareAndSet(
                    false,
                    true)) {

                throw new AssertionError(
                        "Nested provisioning coordination is not expected");
            }

            try {

                return work.get();

            } finally {

                inScope.set(
                        false);
            }
        }

        int executionCount() {

            return executions.get();
        }

        boolean inScope() {

            return inScope.get();
        }

        String issuer() {

            return issuer;
        }

        String subject() {

            return subject;
        }
    }
}

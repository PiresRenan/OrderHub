package io.github.piresrenan.orderhub.orders.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

class CreateOrderIdempotencyPersistencePortContractTest {

    private static final String REPOSITORY =
            "io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository";

    private static final String ACQUISITION =
            "io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition";

    private static final String COMPLETION =
            "io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion";

    private static final String PERSISTENCE_EXCEPTION =
            "io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyPersistenceException";

    private static final String IN_PROGRESS_EXCEPTION =
            "io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyInProgressException";

    @Test
    void exposesTypedDurableAcquisitionAndCompletionContract()
            throws Exception {

        var repository =
                loadType(
                        REPOSITORY);

        assertThat(repository)
                .as(
                        "CreateOrderIdempotencyRepository must exist")
                .isNotNull();

        assertThat(repository.isInterface())
                .isTrue();

        var acquisition =
                requireType(
                        ACQUISITION);

        var completion =
                requireType(
                        COMPLETION);

        var persistenceException =
                requireType(
                        PERSISTENCE_EXCEPTION);

        var inProgressException =
                requireType(
                        IN_PROGRESS_EXCEPTION);

        var acquire =
                repository.getMethod(
                        "acquire",
                        UUID.class,
                        CreateOrderIdempotencyKeyDigest.class,
                        CreateOrderRequestFingerprint.class);

        assertThat(acquire.getReturnType())
                .isEqualTo(
                        acquisition);

        var complete =
                repository.getMethod(
                        "complete",
                        UUID.class,
                        CreateOrderIdempotencyKeyDigest.class,
                        CreateOrderRequestFingerprint.class,
                        completion);

        assertThat(complete.getReturnType())
                .isEqualTo(
                        void.class);

        assertThat(completion.isRecord())
                .isTrue();

        assertThat(
                Arrays.stream(
                                completion.getRecordComponents())
                        .map(component ->
                                component.getName())
                        .toList())
                .containsExactly(
                        "orderId",
                        "orderStatus",
                        "allocationOutcome");

        assertThat(
                Arrays.stream(
                                completion.getRecordComponents())
                        .map(component ->
                                component.getType()
                                        .getName())
                        .toList())
                .containsExactly(
                        UUID.class.getName(),
                        OrderStatus.class.getName(),
                        CreateOrderAllocationOutcome.class.getName());

        assertThat(acquisition.isSealed())
                .isTrue();

        assertThat(
                Arrays.stream(
                                acquisition.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .toList())
                .containsExactlyInAnyOrder(
                        "Acquired",
                        "Replay",
                        "FingerprintConflict");

        var replay =
                Arrays.stream(
                                acquisition.getPermittedSubclasses())
                        .filter(type ->
                                type.getSimpleName()
                                        .equals(
                                                "Replay"))
                        .findFirst()
                        .orElseThrow();

        assertThat(replay.isRecord())
                .isTrue();

        assertThat(replay.getRecordComponents())
                .hasSize(
                        1);

        assertThat(replay.getRecordComponents()[0].getName())
                .isEqualTo(
                        "completion");

        assertThat(replay.getRecordComponents()[0].getType())
                .isEqualTo(
                        completion);

        assertThat(RuntimeException.class)
                .isAssignableFrom(
                        persistenceException);

        assertThat(RuntimeException.class)
                .isAssignableFrom(
                        inProgressException);
    }

    private static Class<?> requireType(
            String name)
            throws ClassNotFoundException {

        return Class.forName(
                name);
    }

    private static Class<?> loadType(
            String name) {

        try {
            return Class.forName(
                    name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}

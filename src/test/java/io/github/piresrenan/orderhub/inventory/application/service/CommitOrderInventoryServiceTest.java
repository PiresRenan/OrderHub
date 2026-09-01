package io.github.piresrenan.orderhub.inventory.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryOperationException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentIdGenerator;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPersistenceException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryTimeProvider;
import io.github.piresrenan.orderhub.inventory.domain.model.InsufficientInventoryException;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryAllocation;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryCommitment;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPosition;

class CommitOrderInventoryServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_A =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_B =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000002");

    private static final UUID COMMITMENT_A =
            UUID.fromString(
                    "40000000-0000-0000-0000-000000000001");

    private static final UUID COMMITMENT_B =
            UUID.fromString(
                    "40000000-0000-0000-0000-000000000002");

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-09-01T12:34:56.123456Z");

    @Test
    void aggregatesDuplicateVariantsAndCommitsInDeterministicOrder() {

        var policies =
                new StubPolicyRepository(
                        Optional.of(
                                InventoryPolicy.DENY));

        var positions =
                new RecordingPositionRepository();

        var commitments =
                new RecordingCommitmentRepository();

        var service =
                service(
                        policies,
                        positions,
                        commitments,
                        COMMITMENT_A,
                        COMMITMENT_B);

        service.commit(
                new CommitOrderInventoryCommand(
                        TENANT_ID,
                        ORDER_ID,
                        List.of(
                                demand(
                                        VARIANT_B,
                                        4),
                                demand(
                                        VARIANT_A,
                                        2),
                                demand(
                                        VARIANT_A,
                                        3))));

        assertThat(positions.calls)
                .containsExactly(
                        new CommitCall(
                                TENANT_ID,
                                VARIANT_A,
                                5,
                                InventoryPolicy.DENY),
                        new CommitCall(
                                TENANT_ID,
                                VARIANT_B,
                                4,
                                InventoryPolicy.DENY));

        assertThat(commitments.saved)
                .hasSize(2);

        var first =
                commitments.saved.get(0);

        assertThat(first.commitmentId())
                .isEqualTo(COMMITMENT_A);

        assertThat(first.tenantId())
                .isEqualTo(TENANT_ID);

        assertThat(first.orderId())
                .isEqualTo(ORDER_ID);

        assertThat(first.variantId())
                .isEqualTo(VARIANT_A);

        assertThat(first.requestedQuantity())
                .isEqualTo(5);

        assertThat(first.allocatedQuantity())
                .isEqualTo(5);

        assertThat(first.backorderedQuantity())
                .isZero();

        assertThat(first.createdAt())
                .isEqualTo(CREATED_AT);

        var second =
                commitments.saved.get(1);

        assertThat(second.commitmentId())
                .isEqualTo(COMMITMENT_B);

        assertThat(second.variantId())
                .isEqualTo(VARIANT_B);

        assertThat(second.requestedQuantity())
                .isEqualTo(4);
    }

    @Test
    void persistsBackorderAllocationOutcome() {

        var policies =
                new StubPolicyRepository(
                        Optional.of(
                                InventoryPolicy.ALLOW_BACKORDER));

        var positions =
                new RecordingPositionRepository();

        positions.allocations.put(
                VARIANT_A,
                new InventoryAllocation(
                        InventoryPosition.create(
                                TENANT_ID,
                                VARIANT_A,
                                2,
                                2,
                                3,
                                0),
                        5,
                        2,
                        3));

        var commitments =
                new RecordingCommitmentRepository();

        var service =
                service(
                        policies,
                        positions,
                        commitments,
                        COMMITMENT_A);

        service.commit(
                command(
                        VARIANT_A,
                        5));

        assertThat(commitments.saved)
                .singleElement()
                .satisfies(commitment -> {

                    assertThat(
                            commitment.requestedQuantity())
                            .isEqualTo(5);

                    assertThat(
                            commitment.allocatedQuantity())
                            .isEqualTo(2);

                    assertThat(
                            commitment.backorderedQuantity())
                            .isEqualTo(3);
                });
    }

    @Test
    void failsClosedBeforeMutationWhenTenantPolicyIsMissing() {

        var policies =
                new StubPolicyRepository(
                        Optional.empty());

        var positions =
                new RecordingPositionRepository();

        var commitments =
                new RecordingCommitmentRepository();

        var service =
                service(
                        policies,
                        positions,
                        commitments,
                        COMMITMENT_A);

        assertThatThrownBy(() ->
                service.commit(
                        command(
                                VARIANT_A,
                                1)))
                .isInstanceOf(
                        InventoryCommitmentRejectedException.class)
                .hasMessage(
                        "Inventory commitment could not be accepted.");

        assertThat(positions.calls)
                .isEmpty();

        assertThat(commitments.saved)
                .isEmpty();
    }

    @Test
    void translatesMissingOrInsufficientPositionToPublicRejection() {

        var policies =
                new StubPolicyRepository(
                        Optional.of(
                                InventoryPolicy.DENY));

        var positions =
                new RecordingPositionRepository();

        positions.failure =
                new InsufficientInventoryException();

        var commitments =
                new RecordingCommitmentRepository();

        var service =
                service(
                        policies,
                        positions,
                        commitments,
                        COMMITMENT_A);

        assertThatThrownBy(() ->
                service.commit(
                        command(
                                VARIANT_A,
                                1)))
                .isInstanceOf(
                        InventoryCommitmentRejectedException.class)
                .hasMessage(
                        "Inventory commitment could not be accepted.");

        assertThat(commitments.saved)
                .isEmpty();
    }

    @Test
    void translatesPolicyPersistenceFailureToPublicTechnicalFailure() {

        var internalFailure =
                new InventoryPersistenceException(
                        new IllegalStateException(
                                "synthetic-policy-storage-failure"));

        var policies =
                new StubPolicyRepository(
                        Optional.empty());

        policies.failure =
                internalFailure;

        var service =
                service(
                        policies,
                        new RecordingPositionRepository(),
                        new RecordingCommitmentRepository(),
                        COMMITMENT_A);

        assertThatThrownBy(() ->
                service.commit(
                        command(
                                VARIANT_A,
                                1)))
                .isInstanceOf(
                        InventoryOperationException.class)
                .hasMessage(
                        "Inventory operation could not be completed.")
                .hasCause(
                        internalFailure);
    }

    @Test
    void translatesCommitmentPersistenceFailureToPublicTechnicalFailure() {

        var policies =
                new StubPolicyRepository(
                        Optional.of(
                                InventoryPolicy.DENY));

        var positions =
                new RecordingPositionRepository();

        var internalFailure =
                new InventoryPersistenceException(
                        new IllegalStateException(
                                "synthetic-ledger-storage-failure"));

        var commitments =
                new RecordingCommitmentRepository();

        commitments.failure =
                internalFailure;

        var service =
                service(
                        policies,
                        positions,
                        commitments,
                        COMMITMENT_A);

        assertThatThrownBy(() ->
                service.commit(
                        command(
                                VARIANT_A,
                                1)))
                .isInstanceOf(
                        InventoryOperationException.class)
                .hasCause(
                        internalFailure);

        assertThat(positions.calls)
                .hasSize(1);
    }

    @Test
    void rejectsAggregatedQuantityOverflowBeforeInventoryMutation() {

        var policies =
                new StubPolicyRepository(
                        Optional.of(
                                InventoryPolicy.DENY));

        var positions =
                new RecordingPositionRepository();

        var service =
                service(
                        policies,
                        positions,
                        new RecordingCommitmentRepository(),
                        COMMITMENT_A);

        var command =
                new CommitOrderInventoryCommand(
                        TENANT_ID,
                        ORDER_ID,
                        List.of(
                                demand(
                                        VARIANT_A,
                                        Long.MAX_VALUE),
                                demand(
                                        VARIANT_A,
                                        1)));

        assertThatThrownBy(() ->
                service.commit(
                        command))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Aggregated inventory demand exceeds supported quantity range");

        assertThat(positions.calls)
                .isEmpty();
    }

    private static CommitOrderInventoryService service(
            InventoryPolicyRepository policies,
            InventoryPositionRepository positions,
            InventoryCommitmentRepository commitments,
            UUID... commitmentIds) {

        return new CommitOrderInventoryService(
                policies,
                positions,
                commitments,
                new SequenceCommitmentIdGenerator(
                        commitmentIds),
                new FixedInventoryTimeProvider(
                        CREATED_AT));
    }

    private static CommitOrderInventoryCommand command(
            UUID variantId,
            long quantity) {

        return new CommitOrderInventoryCommand(
                TENANT_ID,
                ORDER_ID,
                List.of(
                        demand(
                                variantId,
                                quantity)));
    }

    private static CommitOrderInventoryCommand.Demand demand(
            UUID variantId,
            long quantity) {

        return new CommitOrderInventoryCommand.Demand(
                variantId,
                quantity);
    }

    private static final class StubPolicyRepository
            implements InventoryPolicyRepository {

        private final Optional<InventoryPolicy> policy;
        private RuntimeException failure;

        StubPolicyRepository(
                Optional<InventoryPolicy> policy) {

            this.policy =
                    policy;
        }

        @Override
        public Optional<InventoryPolicy> findByTenantId(
                UUID tenantId) {

            if (failure != null) {
                throw failure;
            }

            return policy;
        }
    }

    private static final class RecordingPositionRepository
            implements InventoryPositionRepository {

        private final List<CommitCall> calls =
                new ArrayList<>();

        private final Map<UUID, InventoryAllocation> allocations =
                new HashMap<>();

        private RuntimeException failure;

        @Override
        public Optional<InventoryPosition> findById(
                UUID tenantId,
                UUID variantId) {

            return Optional.empty();
        }

        @Override
        public InventoryAllocation commit(
                UUID tenantId,
                UUID variantId,
                long requestedQuantity,
                InventoryPolicy policy) {

            calls.add(
                    new CommitCall(
                            tenantId,
                            variantId,
                            requestedQuantity,
                            policy));

            if (failure != null) {
                throw failure;
            }

            var configured =
                    allocations.get(
                            variantId);

            if (configured != null) {
                return configured;
            }

            return new InventoryAllocation(
                    InventoryPosition.create(
                            tenantId,
                            variantId,
                            1_000_000,
                            requestedQuantity,
                            0,
                            0),
                    requestedQuantity,
                    requestedQuantity,
                    0);
        }
    }

    private static final class RecordingCommitmentRepository
            implements InventoryCommitmentRepository {

        private final List<InventoryCommitment> saved =
                new ArrayList<>();

        private RuntimeException failure;

        @Override
        public InventoryCommitment save(
                InventoryCommitment commitment) {

            if (failure != null) {
                throw failure;
            }

            saved.add(
                    commitment);

            return commitment;
        }

        @Override
        public Optional<InventoryCommitment> findByOrderAndVariant(
                UUID tenantId,
                UUID orderId,
                UUID variantId) {

            return Optional.empty();
        }
    }

    private static final class SequenceCommitmentIdGenerator
            implements InventoryCommitmentIdGenerator {

        private final ArrayDeque<UUID> ids;

        SequenceCommitmentIdGenerator(
                UUID... ids) {

            this.ids =
                    new ArrayDeque<>(
                            List.of(
                                    ids));
        }

        @Override
        public UUID generate() {

            return ids.removeFirst();
        }
    }

    private record FixedInventoryTimeProvider(
            Instant instant)
            implements InventoryTimeProvider {

        @Override
        public Instant now() {

            return instant;
        }
    }

    private record CommitCall(
            UUID tenantId,
            UUID variantId,
            long requestedQuantity,
            InventoryPolicy policy) {
    }
}
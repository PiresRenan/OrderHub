package io.github.piresrenan.orderhub.inventory.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAllocationOutcome;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentIdGenerator;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryTimeProvider;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryAllocation;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryCommitment;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPosition;

class CommitOrderInventoryOutcomeTest {

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

    @Test
    void reportsFullyAllocatedWhenEveryAcceptedUnitIsPhysicallyAllocated() {

        var service =
                service(
                        Map.of(
                                VARIANT_A,
                                allocation(
                                        VARIANT_A,
                                        5,
                                        5,
                                        0)));

        var outcome =
                service.commit(
                        command(
                                demand(
                                        VARIANT_A,
                                        5)));

        assertThat(outcome)
                .isEqualTo(
                        InventoryAllocationOutcome.FULLY_ALLOCATED);
    }

    @Test
    void reportsPartiallyBackorderedWhenOneDemandIsOnlyPartiallyAllocated() {

        var service =
                service(
                        Map.of(
                                VARIANT_A,
                                allocation(
                                        VARIANT_A,
                                        5,
                                        2,
                                        3)));

        var outcome =
                service.commit(
                        command(
                                demand(
                                        VARIANT_A,
                                        5)));

        assertThat(outcome)
                .isEqualTo(
                        InventoryAllocationOutcome.PARTIALLY_BACKORDERED);
    }

    @Test
    void reportsFullyBackorderedWhenNoAcceptedUnitIsPhysicallyAllocated() {

        var service =
                service(
                        Map.of(
                                VARIANT_A,
                                allocation(
                                        VARIANT_A,
                                        5,
                                        0,
                                        5)));

        var outcome =
                service.commit(
                        command(
                                demand(
                                        VARIANT_A,
                                        5)));

        assertThat(outcome)
                .isEqualTo(
                        InventoryAllocationOutcome.FULLY_BACKORDERED);
    }

    @Test
    void reportsPartialAcrossVariantsWhenSomeUnitsAllocateAndOthersFullyBackorder() {

        var service =
                service(
                        Map.of(
                                VARIANT_A,
                                allocation(
                                        VARIANT_A,
                                        2,
                                        2,
                                        0),
                                VARIANT_B,
                                allocation(
                                        VARIANT_B,
                                        3,
                                        0,
                                        3)));

        var outcome =
                service.commit(
                        command(
                                demand(
                                        VARIANT_A,
                                        2),
                                demand(
                                        VARIANT_B,
                                        3)));

        assertThat(outcome)
                .isEqualTo(
                        InventoryAllocationOutcome.PARTIALLY_BACKORDERED);
    }

    private static CommitOrderInventoryService service(
            Map<UUID, InventoryAllocation> allocations) {

        return new CommitOrderInventoryService(
                tenantId ->
                        Optional.of(
                                InventoryPolicy.ALLOW_BACKORDER),
                new AllocationRepository(
                        allocations),
                new NoOpCommitmentRepository(),
                new RandomCommitmentIdGenerator(),
                new FixedTimeProvider());
    }

    private static CommitOrderInventoryCommand command(
            CommitOrderInventoryCommand.Demand... demands) {

        return new CommitOrderInventoryCommand(
                TENANT_ID,
                ORDER_ID,
                List.of(
                        demands));
    }

    private static CommitOrderInventoryCommand.Demand demand(
            UUID variantId,
            long quantity) {

        return new CommitOrderInventoryCommand.Demand(
                variantId,
                quantity);
    }

    private static InventoryAllocation allocation(
            UUID variantId,
            long requested,
            long allocated,
            long backordered) {

        return new InventoryAllocation(
                InventoryPosition.create(
                        TENANT_ID,
                        variantId,
                        allocated,
                        allocated,
                        backordered,
                        0),
                requested,
                allocated,
                backordered);
    }

    private static final class AllocationRepository
            implements InventoryPositionRepository {

        private final Map<UUID, InventoryAllocation> allocations;

        AllocationRepository(
                Map<UUID, InventoryAllocation> allocations) {

            this.allocations =
                    new HashMap<>(
                            allocations);
        }

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

            var allocation =
                    allocations.get(
                            variantId);

            if (allocation == null) {
                throw new AssertionError(
                        "Missing synthetic allocation");
            }

            return allocation;
        }
    }

    private static final class NoOpCommitmentRepository
            implements InventoryCommitmentRepository {

        @Override
        public InventoryCommitment save(
                InventoryCommitment commitment) {

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

    private static final class RandomCommitmentIdGenerator
            implements InventoryCommitmentIdGenerator {

        @Override
        public UUID generate() {
            return UUID.randomUUID();
        }
    }

    private static final class FixedTimeProvider
            implements InventoryTimeProvider {

        @Override
        public Instant now() {
            return Instant.parse(
                    "2026-09-01T12:34:56Z");
        }
    }
}
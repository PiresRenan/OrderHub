package io.github.piresrenan.orderhub.inventory.application.service;

import java.util.Objects;
import java.util.TreeMap;

import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryOperationException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentIdGenerator;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPersistenceException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryTimeProvider;
import io.github.piresrenan.orderhub.inventory.domain.model.InsufficientInventoryException;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryCommitment;

/**
 * Commits all Inventory demand belonging to one Order.
 *
 * <p>
 * The service deliberately does not open a transaction. Its mutations and
 * ledger writes participate in the transaction owned by the calling business
 * use case.
 * </p>
 */
public final class CommitOrderInventoryService
        implements CommitOrderInventoryUseCase {

    private final InventoryPolicyRepository policyRepository;
    private final InventoryPositionRepository positionRepository;
    private final InventoryCommitmentRepository commitmentRepository;
    private final InventoryCommitmentIdGenerator commitmentIdGenerator;
    private final InventoryTimeProvider timeProvider;

    public CommitOrderInventoryService(
            InventoryPolicyRepository policyRepository,
            InventoryPositionRepository positionRepository,
            InventoryCommitmentRepository commitmentRepository,
            InventoryCommitmentIdGenerator commitmentIdGenerator,
            InventoryTimeProvider timeProvider) {

        this.policyRepository =
                Objects.requireNonNull(
                        policyRepository,
                        "policyRepository");

        this.positionRepository =
                Objects.requireNonNull(
                        positionRepository,
                        "positionRepository");

        this.commitmentRepository =
                Objects.requireNonNull(
                        commitmentRepository,
                        "commitmentRepository");

        this.commitmentIdGenerator =
                Objects.requireNonNull(
                        commitmentIdGenerator,
                        "commitmentIdGenerator");

        this.timeProvider =
                Objects.requireNonNull(
                        timeProvider,
                        "timeProvider");
    }

    @Override
    public void commit(
            CommitOrderInventoryCommand command) {

        Objects.requireNonNull(
                command,
                "command");

        /*
         * Complete aggregation before touching persistent state.
         *
         * TreeMap gives every process the same UUID ordering, reducing
         * multi-Variant deadlock risk without process-local locks.
         */
        var aggregatedDemand =
                aggregate(
                        command);

        try {

            var policy =
                    policyRepository
                            .findByTenantId(
                                    command.tenantId())
                            .orElseThrow(
                                    InventoryCommitmentRejectedException::new);

            for (var demand :
                    aggregatedDemand.entrySet()) {

                var variantId =
                        demand.getKey();

                var requestedQuantity =
                        demand.getValue();

                var allocation =
                        commitPosition(
                                command,
                                variantId,
                                requestedQuantity,
                                policy);

                var commitment =
                        InventoryCommitment.create(
                                commitmentIdGenerator.generate(),
                                command.tenantId(),
                                command.orderId(),
                                variantId,
                                requestedQuantity,
                                allocation.allocatedQuantity(),
                                allocation.backorderedQuantity(),
                                timeProvider.now());

                commitmentRepository.save(
                        commitment);
            }

        } catch (InventoryPersistenceException exception) {

            throw new InventoryOperationException(
                    exception);
        }
    }

    private io.github.piresrenan.orderhub.inventory.domain.model.InventoryAllocation
            commitPosition(
                    CommitOrderInventoryCommand command,
                    java.util.UUID variantId,
                    long requestedQuantity,
                    io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy policy) {

        try {

            return positionRepository.commit(
                    command.tenantId(),
                    variantId,
                    requestedQuantity,
                    policy);

        } catch (InsufficientInventoryException exception) {

            /*
             * Deliberately collapse missing InventoryPosition and insufficient
             * availability into the same privacy-safe module contract.
             */
            throw new InventoryCommitmentRejectedException();
        }
    }

    private static TreeMap<java.util.UUID, Long> aggregate(
            CommitOrderInventoryCommand command) {

        var aggregated =
                new TreeMap<java.util.UUID, Long>();

        for (var demand :
                command.demands()) {

            try {

                aggregated.merge(
                        demand.variantId(),
                        demand.quantity(),
                        Math::addExact);

            } catch (ArithmeticException exception) {

                throw new IllegalArgumentException(
                        "Aggregated inventory demand exceeds supported quantity range",
                        exception);
            }
        }

        return aggregated;
    }
}
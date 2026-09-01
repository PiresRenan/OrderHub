package io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Predicate;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionTimedOutException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityRejectedException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;

/**
 * Micrometer decorator for create-Order operational outcomes.
 *
 * <p>
 * Metric dimensions are deliberately bounded. Business identifiers such as
 * tenant, Order, Variant, Product, SKU and Customer never become meter tags.
 * Individual diagnostics continue through request/correlation tracing.
 * </p>
 */
public final class MicrometerObservedCreateOrderUseCase
        implements CreateOrderUseCase {

    private static final String ALLOCATION_METRIC =
            "orderhub.orders.create.allocation";

    private static final String FAILURE_METRIC =
            "orderhub.orders.create.failure";

    private static final String OUTCOME_TAG =
            "outcome";

    private static final String REASON_TAG =
            "reason";

    private final CreateOrderUseCase delegate;
    private final MeterRegistry meterRegistry;

    public MicrometerObservedCreateOrderUseCase(
            CreateOrderUseCase delegate,
            MeterRegistry meterRegistry) {

        this.delegate =
                Objects.requireNonNull(
                        delegate,
                        "delegate");

        this.meterRegistry =
                Objects.requireNonNull(
                        meterRegistry,
                        "meterRegistry");
    }

    @Override
    public CreateOrderResult create(
            CreateOrderCommand command) {

        try {
            var result =
                    delegate.create(
                            command);

            recordAllocationOutcome(
                    result.allocationOutcome());

            return result;

        } catch (RuntimeException failure) {

            recordFailure(
                    classifyFailure(
                            failure));

            throw failure;
        }
    }

    private void recordAllocationOutcome(
            CreateOrderAllocationOutcome outcome) {

        Counter.builder(
                        ALLOCATION_METRIC)
                .description(
                        "Successful Order creation by Inventory allocation outcome")
                .tag(
                        OUTCOME_TAG,
                        allocationOutcomeTag(
                                outcome))
                .register(
                        meterRegistry)
                .increment();
    }

    private void recordFailure(
            String reason) {

        Counter.builder(
                        FAILURE_METRIC)
                .description(
                        "Failed Order creation by bounded operational reason")
                .tag(
                        REASON_TAG,
                        reason)
                .register(
                        meterRegistry)
                .increment();
    }

    private static String allocationOutcomeTag(
            CreateOrderAllocationOutcome outcome) {

        Objects.requireNonNull(
                outcome,
                "outcome");

        return switch (outcome) {
            case FULLY_ALLOCATED ->
                    "fully_allocated";
            case PARTIALLY_BACKORDERED ->
                    "partially_backordered";
            case FULLY_BACKORDERED ->
                    "fully_backordered";
        };
    }

    private static String classifyFailure(
            RuntimeException failure) {

        if (containsCause(
                failure,
                CatalogOrderabilityRejectedException.class::isInstance)) {

            return "catalog_item_unavailable";
        }
        if (containsCause(
                failure,
                InventoryCommitmentRejectedException.class::isInstance)) {

            return "insufficient_inventory";
        }

        /*
         * Classification is deliberately performed in priority passes.
         *
         * A Spring transient wrapper can contain a PostgreSQL deadlock or
         * timeout cause. Looking for the precise cause first prevents the
         * broad transient category from hiding a more useful bounded reason.
         */
        if (containsCause(
                failure,
                MicrometerObservedCreateOrderUseCase::isLockTimeout)) {

            return "lock_timeout";
        }

        if (containsCause(
                failure,
                MicrometerObservedCreateOrderUseCase::isDeadlock)) {

            return "deadlock";
        }

        if (containsCause(
                failure,
                MicrometerObservedCreateOrderUseCase::isTransientDatabaseFailure)) {

            return "transient_database";
        }

        return "technical_failure";
    }

    private static boolean isLockTimeout(
            Throwable failure) {

        if (failure instanceof TransactionTimedOutException
                || failure instanceof SQLTimeoutException) {

            return true;
        }

        if (failure instanceof SQLException sqlException) {

            var sqlState =
                    sqlException.getSQLState();

            return "57014".equals(sqlState)
                    || "55P03".equals(sqlState);
        }

        return false;
    }

    private static boolean isDeadlock(
            Throwable failure) {

        if (!(failure instanceof SQLException sqlException)) {
            return false;
        }

        return "40P01".equals(
                sqlException.getSQLState());
    }

    private static boolean isTransientDatabaseFailure(
            Throwable failure) {

        if (failure instanceof SQLTransientException
                || failure instanceof TransientDataAccessException) {

            return true;
        }

        if (!(failure instanceof SQLException sqlException)) {
            return false;
        }

        var sqlState =
                sqlException.getSQLState();

        if (sqlState == null
                || sqlState.length() < 2) {

            return false;
        }

        return sqlState.startsWith("08")
                || sqlState.startsWith("40");
    }

    private static boolean containsCause(
            Throwable failure,
            Predicate<Throwable> predicate) {

        var seen =
                Collections.newSetFromMap(
                        new IdentityHashMap<Throwable, Boolean>());

        var current =
                failure;

        while (current != null
                && seen.add(current)) {

            if (predicate.test(current)) {
                return true;
            }

            current =
                    current.getCause();
        }

        return false;
    }
}

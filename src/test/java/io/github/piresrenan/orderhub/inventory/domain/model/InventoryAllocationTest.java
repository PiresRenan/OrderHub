package io.github.piresrenan.orderhub.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class InventoryAllocationTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static InventoryPosition validPosition() {

        return InventoryPosition.create(
                TENANT_ID,
                VARIANT_ID,
                10,
                5,
                3,
                0);
    }

    @Test
    void createsReconciledAllocation() {

        var position =
                validPosition();

        var allocation =
                new InventoryAllocation(
                        position,
                        8,
                        5,
                        3);

        assertThat(allocation.position())
                .isSameAs(position);

        assertThat(allocation.requestedQuantity())
                .isEqualTo(8);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(5);

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(3);
    }

    @Test
    void rejectsMissingResultingPosition() {

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        null,
                        1,
                        1,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation resulting position is required");
    }

    @Test
    void rejectsNonPositiveRequestedQuantity() {

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        validPosition(),
                        0,
                        0,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation requested quantity must be greater than zero");

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        validPosition(),
                        -1,
                        0,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation requested quantity must be greater than zero");
    }

    @Test
    void rejectsNegativeAllocatedQuantity() {

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        validPosition(),
                        1,
                        -1,
                        2))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation allocated quantity must not be negative");
    }

    @Test
    void rejectsNegativeBackorderedQuantity() {

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        validPosition(),
                        1,
                        1,
                        -1))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation backordered quantity must not be negative");
    }

    @Test
    void rejectsQuantitiesThatDoNotReconcile() {

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        validPosition(),
                        10,
                        6,
                        3))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation quantities must satisfy requested = allocated + backordered");
    }

    @Test
    void rejectsAllocatedQuantityAboveRequestedQuantity() {

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        validPosition(),
                        10,
                        11,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation quantities must satisfy requested = allocated + backordered");
    }

    @Test
    void reconcilesMaximumSupportedQuantityWithoutOverflow() {

        var allocation =
                new InventoryAllocation(
                        validPosition(),
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        0);

        assertThat(allocation.requestedQuantity())
                .isEqualTo(Long.MAX_VALUE);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(Long.MAX_VALUE);

        assertThat(allocation.backorderedQuantity())
                .isZero();
    }

    @Test
    void rejectsMaximumQuantityMismatchWithoutArithmeticOverflow() {

        assertThatThrownBy(() ->
                new InventoryAllocation(
                        validPosition(),
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        1))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory allocation quantities must satisfy requested = allocated + backordered");
    }
}
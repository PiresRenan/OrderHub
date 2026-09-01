package io.github.piresrenan.orderhub.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class InventoryPositionTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    @Test
    void createsValidInventoryPosition() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        100,
                        20,
                        7,
                        10);

        assertThat(position.tenantId())
                .isEqualTo(TENANT_ID);

        assertThat(position.variantId())
                .isEqualTo(VARIANT_ID);

        assertThat(position.onHand())
                .isEqualTo(100);

        assertThat(position.committed())
                .isEqualTo(20);

        assertThat(position.backordered())
                .isEqualTo(7);

        assertThat(position.safetyStock())
                .isEqualTo(10);
    }

    @Test
    void calculatesPositiveAvailableToPromise() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        100,
                        20,
                        0,
                        10);

        assertThat(position.availableToPromise())
                .isEqualTo(70);
    }

    @Test
    void preservesNegativeAvailableToPromise() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        8,
                        0,
                        5);

        assertThat(position.availableToPromise())
                .isEqualTo(-3);
    }

    @Test
    void rejectsMissingTenantIdentity() {

        assertThatThrownBy(() ->
                InventoryPosition.create(
                        null,
                        VARIANT_ID,
                        10,
                        0,
                        0,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory tenant id is required");
    }

    @Test
    void rejectsMissingVariantIdentity() {

        assertThatThrownBy(() ->
                InventoryPosition.create(
                        TENANT_ID,
                        null,
                        10,
                        0,
                        0,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory variant id is required");
    }

    @Test
    void rejectsNegativeOnHandQuantity() {

        assertThatThrownBy(() ->
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        -1,
                        0,
                        0,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory on-hand quantity must not be negative");
    }

    @Test
    void rejectsNegativeCommittedQuantity() {

        assertThatThrownBy(() ->
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        -1,
                        0,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory committed quantity must not be negative");
    }

    @Test
    void rejectsNegativeBackorderedQuantity() {

        assertThatThrownBy(() ->
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        0,
                        -1,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory backordered quantity must not be negative");
    }

    @Test
    void rejectsNegativeSafetyStock() {

        assertThatThrownBy(() ->
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        0,
                        0,
                        -1))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory safety stock must not be negative");
    }

    @Test
    void rejectsCommittedQuantityAbovePhysicalStock() {

        assertThatThrownBy(() ->
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        11,
                        0,
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory committed quantity must not exceed on-hand quantity");
    }

    @Test
    void rejectsNonPositiveRequestedCommitQuantity() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        0,
                        0,
                        0);

        assertThatThrownBy(() ->
                position.commit(
                        0,
                        InventoryPolicy.DENY))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory requested quantity must be greater than zero");

        assertThatThrownBy(() ->
                position.commit(
                        -1,
                        InventoryPolicy.DENY))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory requested quantity must be greater than zero");
    }

    @Test
    void requiresInventoryPolicyWhenCommittingDemand() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        0,
                        0,
                        0);

        assertThatThrownBy(() ->
                position.commit(
                        1,
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory policy is required");
    }

    @Test
    void denyPolicyAllocatesEntireRequestWhenStockIsAvailable() {

        var original =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        2,
                        1,
                        1);

        var allocation =
                original.commit(
                        4,
                        InventoryPolicy.DENY);

        assertThat(allocation.requestedQuantity())
                .isEqualTo(4);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(4);

        assertThat(allocation.backorderedQuantity())
                .isZero();

        assertThat(allocation.position().onHand())
                .isEqualTo(10);

        assertThat(allocation.position().committed())
                .isEqualTo(6);

        assertThat(allocation.position().backordered())
                .isEqualTo(1);

        assertThat(allocation.position().safetyStock())
                .isEqualTo(1);

        assertThat(allocation.position().availableToPromise())
                .isEqualTo(3);
    }

    @Test
    void denyPolicyAllowsRequestExactlyAtAvailableToPromiseBoundary() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        3,
                        0,
                        2);

        var allocation =
                position.commit(
                        5,
                        InventoryPolicy.DENY);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(5);

        assertThat(allocation.backorderedQuantity())
                .isZero();

        assertThat(allocation.position().availableToPromise())
                .isZero();
    }

    @Test
    void denyPolicyRejectsRequestAboveAvailableToPromise() {

        var original =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        3,
                        2,
                        2);

        assertThatThrownBy(() ->
                original.commit(
                        6,
                        InventoryPolicy.DENY))
                .isInstanceOf(
                        InsufficientInventoryException.class)
                .hasMessage(
                        "Insufficient inventory to commit requested quantity.");

        assertThat(original.committed())
                .isEqualTo(3);

        assertThat(original.backordered())
                .isEqualTo(2);
    }

    @Test
    void denyPolicyRejectsDemandWhenAvailableToPromiseIsNegative() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        8,
                        0,
                        5);

        assertThat(position.availableToPromise())
                .isEqualTo(-3);

        assertThatThrownBy(() ->
                position.commit(
                        1,
                        InventoryPolicy.DENY))
                .isInstanceOf(
                        InsufficientInventoryException.class);
    }

    @Test
    void backorderPolicySplitsRequestBetweenAllocationAndBackorder() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        3,
                        0,
                        2);

        var allocation =
                position.commit(
                        8,
                        InventoryPolicy.ALLOW_BACKORDER);

        assertThat(allocation.requestedQuantity())
                .isEqualTo(8);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(5);

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(3);

        assertThat(allocation.position().committed())
                .isEqualTo(8);

        assertThat(allocation.position().backordered())
                .isEqualTo(3);

        assertThat(allocation.position().availableToPromise())
                .isZero();
    }

    @Test
    void backorderPolicyBackordersEntireRequestWhenNoStockIsAvailable() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        8,
                        4,
                        2);

        var allocation =
                position.commit(
                        3,
                        InventoryPolicy.ALLOW_BACKORDER);

        assertThat(allocation.allocatedQuantity())
                .isZero();

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(3);

        assertThat(allocation.position().committed())
                .isEqualTo(8);

        assertThat(allocation.position().backordered())
                .isEqualTo(7);
    }

    @Test
    void backorderPolicyBackordersEntireRequestWhenAvailableToPromiseIsNegative() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        8,
                        4,
                        5);

        assertThat(position.availableToPromise())
                .isEqualTo(-3);

        var allocation =
                position.commit(
                        3,
                        InventoryPolicy.ALLOW_BACKORDER);

        assertThat(allocation.allocatedQuantity())
                .isZero();

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(3);

        assertThat(allocation.position().committed())
                .isEqualTo(8);

        assertThat(allocation.position().backordered())
                .isEqualTo(7);
    }

    @Test
    void preservesPreviouslyBackorderedDemandWhenAddingNewBackorder() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        2,
                        2,
                        10,
                        0);

        var allocation =
                position.commit(
                        4,
                        InventoryPolicy.ALLOW_BACKORDER);

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(4);

        assertThat(allocation.position().backordered())
                .isEqualTo(14);
    }

    @Test
    void rejectsBackorderedQuantityOverflow() {

        var position =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        0,
                        0,
                        Long.MAX_VALUE,
                        0);

        assertThatThrownBy(() ->
                position.commit(
                        1,
                        InventoryPolicy.ALLOW_BACKORDER))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory backordered quantity exceeds supported range");
    }

    @Test
    void commitReturnsNewPositionWithoutMutatingOriginalState() {

        var original =
                InventoryPosition.create(
                        TENANT_ID,
                        VARIANT_ID,
                        10,
                        2,
                        1,
                        1);

        var allocation =
                original.commit(
                        3,
                        InventoryPolicy.DENY);

        assertThat(allocation.position())
                .isNotSameAs(original);

        assertThat(original.onHand())
                .isEqualTo(10);

        assertThat(original.committed())
                .isEqualTo(2);

        assertThat(original.backordered())
                .isEqualTo(1);

        assertThat(original.safetyStock())
                .isEqualTo(1);
    }
}
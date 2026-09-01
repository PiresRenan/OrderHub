package io.github.piresrenan.orderhub.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InventoryCommitmentTest {

    private static final UUID COMMITMENT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-09-01T09:00:00Z");

    @Test
    void createsFullyAllocatedCommitment() {

        var commitment =
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        5,
                        5,
                        0,
                        CREATED_AT);

        assertThat(commitment.commitmentId())
                .isEqualTo(COMMITMENT_ID);

        assertThat(commitment.tenantId())
                .isEqualTo(TENANT_ID);

        assertThat(commitment.orderId())
                .isEqualTo(ORDER_ID);

        assertThat(commitment.variantId())
                .isEqualTo(VARIANT_ID);

        assertThat(commitment.requestedQuantity())
                .isEqualTo(5);

        assertThat(commitment.allocatedQuantity())
                .isEqualTo(5);

        assertThat(commitment.backorderedQuantity())
                .isZero();

        assertThat(commitment.createdAt())
                .isEqualTo(CREATED_AT);
    }

    @Test
    void createsPartiallyBackorderedCommitment() {

        var commitment =
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        8,
                        5,
                        3,
                        CREATED_AT);

        assertThat(commitment.requestedQuantity())
                .isEqualTo(8);

        assertThat(commitment.allocatedQuantity())
                .isEqualTo(5);

        assertThat(commitment.backorderedQuantity())
                .isEqualTo(3);
    }

    @Test
    void createsFullyBackorderedCommitment() {

        var commitment =
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        4,
                        0,
                        4,
                        CREATED_AT);

        assertThat(commitment.allocatedQuantity())
                .isZero();

        assertThat(commitment.backorderedQuantity())
                .isEqualTo(4);
    }

    @Test
    void rejectsMissingCommitmentIdentity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        null,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        1,
                        1,
                        0,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment id is required");
    }

    @Test
    void rejectsMissingTenantIdentity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        null,
                        ORDER_ID,
                        VARIANT_ID,
                        1,
                        1,
                        0,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment tenant id is required");
    }

    @Test
    void rejectsMissingOrderIdentity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        null,
                        VARIANT_ID,
                        1,
                        1,
                        0,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment order id is required");
    }

    @Test
    void rejectsMissingVariantIdentity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        null,
                        1,
                        1,
                        0,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment variant id is required");
    }

    @Test
    void rejectsNonPositiveRequestedQuantity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        0,
                        0,
                        0,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment requested quantity must be greater than zero");

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        -1,
                        0,
                        0,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment requested quantity must be greater than zero");
    }

    @Test
    void rejectsNegativeAllocatedQuantity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        1,
                        -1,
                        2,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment allocated quantity must not be negative");
    }

    @Test
    void rejectsNegativeBackorderedQuantity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        1,
                        1,
                        -1,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment backordered quantity must not be negative");
    }

    @Test
    void rejectsCommitmentWhoseQuantitiesDoNotReconcile() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        10,
                        6,
                        3,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment quantities must satisfy requested = allocated + backordered");
    }

    @Test
    void rejectsAllocatedQuantityAboveRequestedQuantity() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        10,
                        11,
                        0,
                        CREATED_AT))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment quantities must satisfy requested = allocated + backordered");
    }

    @Test
    void rejectsMissingCreationTime() {

        assertThatThrownBy(() ->
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        1,
                        1,
                        0,
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment creation time is required");
    }

    @Test
    void preservesExactCreationInstant() {

        var instant =
                Instant.parse(
                        "2026-09-01T09:23:45.123456789Z");

        var commitment =
                InventoryCommitment.create(
                        COMMITMENT_ID,
                        TENANT_ID,
                        ORDER_ID,
                        VARIANT_ID,
                        1,
                        1,
                        0,
                        instant);

        assertThat(commitment.createdAt())
                .isSameAs(instant);
    }
}
package io.github.piresrenan.orderhub.inventory.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CommitOrderInventoryCommandTest {

    @Test
    void acceptsValidOrderDemand() {

        var tenantId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var command =
                new CommitOrderInventoryCommand(
                        tenantId,
                        orderId,
                        List.of(
                                new CommitOrderInventoryCommand.Demand(
                                        variantId,
                                        3)));

        assertThat(command.tenantId())
                .isEqualTo(tenantId);

        assertThat(command.orderId())
                .isEqualTo(orderId);

        assertThat(command.demands())
                .containsExactly(
                        new CommitOrderInventoryCommand.Demand(
                                variantId,
                                3));
    }

    @Test
    void rejectsMissingTenantIdentity() {

        assertThatThrownBy(() ->
                new CommitOrderInventoryCommand(
                        null,
                        UUID.randomUUID(),
                        List.of(
                                validDemand())))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment tenant id is required");
    }

    @Test
    void rejectsMissingOrderIdentity() {

        assertThatThrownBy(() ->
                new CommitOrderInventoryCommand(
                        UUID.randomUUID(),
                        null,
                        List.of(
                                validDemand())))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment order id is required");
    }

    @Test
    void rejectsEmptyDemandCollection() {

        assertThatThrownBy(() ->
                new CommitOrderInventoryCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment must contain at least one demand");
    }

    @Test
    void rejectsNullDemandElement() {

        assertThatThrownBy(() ->
                new CommitOrderInventoryCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        java.util.Arrays.asList(
                                validDemand(),
                                null)))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory commitment demands must not contain null elements");
    }

    @Test
    void rejectsMissingVariantIdentity() {

        assertThatThrownBy(() ->
                new CommitOrderInventoryCommand.Demand(
                        null,
                        1))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory demand variant id is required");
    }

    @Test
    void rejectsNonPositiveDemandQuantity() {

        assertThatThrownBy(() ->
                new CommitOrderInventoryCommand.Demand(
                        UUID.randomUUID(),
                        0))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory demand quantity must be greater than zero");

        assertThatThrownBy(() ->
                new CommitOrderInventoryCommand.Demand(
                        UUID.randomUUID(),
                        -1))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory demand quantity must be greater than zero");
    }

    private static CommitOrderInventoryCommand.Demand validDemand() {

        return new CommitOrderInventoryCommand.Demand(
                UUID.randomUUID(),
                1);
    }
}
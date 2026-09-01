package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class VariantBasePriceTest {

    @Test
    void representsTenantVariantAndExactMoney() {

        var tenantId = UUID.randomUUID();
        var variantId = UUID.randomUUID();
        var money = Money.of("BRL", 199_90L);

        var price = VariantBasePrice.create(tenantId, variantId, money);

        assertThat(price.tenantId()).isEqualTo(tenantId);
        assertThat(price.variantId()).isEqualTo(variantId);
        assertThat(price.money()).isEqualTo(money);
        assertThat(price.currencyCode()).isEqualTo("BRL");
        assertThat(price.minorUnits()).isEqualTo(199_90L);
    }

    @Test
    void acceptsZeroPriceStructurally() {

        var price = VariantBasePrice.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Money.of("USD", 0));

        assertThat(price.minorUnits()).isZero();
    }

    @Test
    void rejectsMissingTenantVariantOrMoney() {

        var tenantId = UUID.randomUUID();
        var variantId = UUID.randomUUID();
        var money = Money.of("BRL", 100);

        assertThatThrownBy(() -> VariantBasePrice.create(null, variantId, money))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base price tenant id is required");

        assertThatThrownBy(() -> VariantBasePrice.create(tenantId, null, money))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base price variant id is required");

        assertThatThrownBy(() -> VariantBasePrice.create(tenantId, variantId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base price money is required");
    }
}
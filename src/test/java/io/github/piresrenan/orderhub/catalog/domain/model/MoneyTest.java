package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void preservesExactCurrencyAndMinorUnits() {

        var money = Money.of("BRL", 12_345L);

        assertThat(money.currencyCode()).isEqualTo("BRL");
        assertThat(money.minorUnits()).isEqualTo(12_345L);
    }

    @Test
    void acceptsZeroMinorUnits() {

        assertThat(Money.of("USD", 0).minorUnits()).isZero();
    }

    @Test
    void acceptsCurrenciesWithDifferentMinorUnitScales() {

        assertThat(Money.of("JPY", 500).currencyCode()).isEqualTo("JPY");
        assertThat(Money.of("BRL", 500).currencyCode()).isEqualTo("BRL");
    }

    @Test
    void rejectsMissingCurrencyCode() {

        assertThatThrownBy(() -> Money.of(null, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Money currency code is required");
    }

    @Test
    void rejectsNonCanonicalCurrencyCode() {

        assertThatThrownBy(() -> Money.of("brl", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Money currency code must use canonical ISO 4217 uppercase representation");
    }

    @Test
    void rejectsUnknownCurrencyCode() {

        assertThatThrownBy(() -> Money.of("ZZZ", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Money currency code must be a recognized ISO 4217 currency");
    }

    @Test
    void rejectsNegativeMinorUnits() {

        assertThatThrownBy(() -> Money.of("BRL", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Money minor units must not be negative");
    }
}
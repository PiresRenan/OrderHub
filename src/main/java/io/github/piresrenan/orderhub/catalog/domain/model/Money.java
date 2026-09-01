package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.Currency;
import java.util.Locale;

/**
 * Exact monetary value represented in currency minor units.
 */
public record Money(
        String currencyCode,
        long minorUnits) {

    public Money {

        if (currencyCode == null) {
            throw new IllegalArgumentException(
                    "Money currency code is required");
        }

        if (
                currencyCode.length() != 3
                || !currencyCode.equals(currencyCode.toUpperCase(Locale.ROOT))
                || !currencyCode.matches("[A-Z]{3}")) {

            throw new IllegalArgumentException(
                    "Money currency code must use canonical ISO 4217 uppercase representation");
        }

        try {
            Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Money currency code must be a recognized ISO 4217 currency",
                    exception);
        }

        if (minorUnits < 0) {
            throw new IllegalArgumentException(
                    "Money minor units must not be negative");
        }
    }

    public static Money of(
            String currencyCode,
            long minorUnits) {

        return new Money(
                currencyCode,
                minorUnits);
    }
}
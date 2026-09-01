package io.github.piresrenan.orderhub.orders.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized technical limits for the Orders transaction boundary.
 *
 * <p>
 * The timeout is a provisional safety boundary rather than a load-derived SLA.
 * It exists so database lock waits and other transactional work cannot remain
 * unbounded.
 * </p>
 *
 * @param timeout maximum duration of one create-Order transaction
 */
@ConfigurationProperties(prefix = "orderhub.orders.transaction")
public record OrderTransactionProperties(
        Duration timeout) {

    public OrderTransactionProperties {

        if (timeout == null) {
            throw new IllegalArgumentException(
                    "Order transaction timeout is required");
        }

        if (timeout.isZero()
                || timeout.isNegative()) {

            throw new IllegalArgumentException(
                    "Order transaction timeout must be greater than zero");
        }

        if (timeout.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Order transaction timeout must use whole seconds");
        }

        if (timeout.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Order transaction timeout exceeds supported range");
        }
    }

    /**
     * Converts the validated external duration to the seconds-based Spring
     * transaction timeout contract without rounding.
     *
     * @return positive timeout in whole seconds
     */
    public int timeoutSeconds() {
        return Math.toIntExact(
                timeout.getSeconds());
    }
}
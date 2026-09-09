package io.github.piresrenan.orderhub.workforce.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized runtime policy for Staff provisioning credentials.
 *
 * <p>The TTL is required configuration. No Java default exists because a
 * missing credential lifetime must fail application composition rather than
 * silently create an unbounded or accidentally changed security policy.</p>
 *
 * @param intentTtl maximum lifetime of one Staff provisioning credential
 */
@ConfigurationProperties(
        prefix = "orderhub.workforce.staff-provisioning")
public record StaffProvisioningProperties(
        Duration intentTtl) {

    public StaffProvisioningProperties {

        if (intentTtl == null) {
            throw new IllegalArgumentException(
                    "Staff provisioning intent TTL is required");
        }

        if (intentTtl.isZero()
                || intentTtl.isNegative()) {

            throw new IllegalArgumentException(
                    "Staff provisioning intent TTL must be positive");
        }
    }
}

/**
 * Owns Organization identity, lifecycle and Organization/Tenant placement.
 *
 * <p>
 * Administration consumes only the explicitly named Authorization, Tenants
 * and Users application contracts below. Persistence remains owner-local;
 * future edges require an explicit update to this declaration.
 * </p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "authorization::administration",
                "tenants::administration",
                "users::api"
        }
)
package io.github.piresrenan.orderhub.organizations;

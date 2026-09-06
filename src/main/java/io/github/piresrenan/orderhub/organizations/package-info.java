/**
 * Owns Organization identity, lifecycle and Organization/Tenant placement.
 *
 * <p>
 * The current domain-only checkpoint intentionally permits no dependency on
 * another OrderHub application module. Future edges require a concrete
 * application contract and an explicit update to this declaration.
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

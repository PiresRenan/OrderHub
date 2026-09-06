/** Thin HTTP adaptation for platform and organization administration. */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "organizations::administration",
                "tenants::administration",
                "security::api"
        })
package io.github.piresrenan.orderhub.administration;

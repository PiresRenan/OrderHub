/** Thin HTTP adaptation for platform and organization administration. */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "organizations::administration",
                "tenants::administration",
                "security::api",
                "users::api",
                "workforce::staff-provisioning",
                "workforce::membership-administration",
                "customers::account-linking",
                "authorization::staff-provisioning"
        })
package io.github.piresrenan.orderhub.administration;

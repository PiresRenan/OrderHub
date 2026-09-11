@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"authorization :: policy-model", "authorization :: tenant-authorization",
                "authorization :: staff-provisioning", "users :: api", "tenants :: operational"}
)
package io.github.piresrenan.orderhub.workforce;

package io.github.piresrenan.orderhub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class OrderHubModularityTests {

    private static final String ORGANIZATIONS_PACKAGE_INFO =
            "io.github.piresrenan.orderhub.organizations.package-info";

    @Test
    void verifiesApplicationModuleBoundaries() {

        ApplicationModules
                .of(OrderHubApplication.class)
                .verify();
    }

    @Test
    void detectsOrdersTenantsAndUsersAsApplicationModules() {

        var modules =
                ApplicationModules.of(
                        OrderHubApplication.class);

        var ordersModule =
                modules.getModuleByName(
                        "orders");

        var tenantsModule =
                modules.getModuleByName(
                        "tenants");

        var usersModule =
                modules.getModuleByName(
                        "users");

        assertThat(ordersModule)
                .as(
                        "Orders must be detected"
                                + " as an application module")
                .isPresent();

        assertThat(tenantsModule)
                .as(
                        "Tenants must be detected"
                                + " as an application module")
                .isPresent();

        assertThat(usersModule)
                .as(
                        "Users must be detected"
                                + " as an application module")
                .isPresent();

        var orders =
                ordersModule.orElseThrow();

        var tenants =
                tenantsModule.orElseThrow();

        var users =
                usersModule.orElseThrow();

        assertThat(orders)
                .isNotSameAs(tenants)
                .isNotSameAs(users);

        assertThat(tenants)
                .isNotSameAs(users);
    }

    @Test
    void detectsOrganizationsAsApplicationModule() {

        var modules =
                ApplicationModules.of(
                        OrderHubApplication.class);

        assertThat(
                modules.getModuleByName(
                        "organizations"))
                .as(
                        "Organizations must be detected"
                                + " as an application module")
                .isPresent();
    }

    @Test
    void declaresOrganizationsAsClosedModuleWithNarrowAdministrationDependencies() {

        var modules =
                ApplicationModules.of(
                        OrderHubApplication.class);

        var organizations =
                modules.getModuleByName(
                                "organizations")
                        .orElseThrow();

        var packageInfoType =
                loadOrganizationsPackageInfo();

        var declaration =
                packageInfoType
                        .getPackage()
                        .getAnnotation(
                                ApplicationModule.class);

        assertThat(declaration)
                .as(
                        "Organizations must explicitly declare"
                                + " its root-package module contract")
                .isNotNull();

        assertThat(declaration.type())
                .as(
                        "Organizations must remain"
                                + " a closed application module")
                .isEqualTo(
                        ApplicationModule.Type.CLOSED);

        assertThat(declaration.allowedDependencies())
                .containsExactlyInAnyOrder(
                        "authorization::administration",
                        "tenants::administration",
                        "users::api");

        assertThat(
                organizations
                        .getAllowedDependencies(
                                modules)
                        .isEmpty())
                .as(
                        "Spring Modulith must resolve the explicitly"
                                + " allowed Organizations dependencies")
                .isFalse();

        assertThat(organizations.isOpen())
                .as(
                        "Organizations must not be"
                                + " an open application module")
                .isFalse();
    }

    private static Class<?> loadOrganizationsPackageInfo() {

        try {
            return Class.forName(
                    ORGANIZATIONS_PACKAGE_INFO);
        }
        catch (ClassNotFoundException exception) {
            throw new AssertionError(
                    "Organizations must explicitly declare"
                            + " package-level module metadata",
                    exception);
        }
    }
}

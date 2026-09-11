package io.github.piresrenan.orderhub.users;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Executable architectural contract for the exposed Users application API.
 *
 * <p>
 * The rule is expressed over packages rather than named types so any public
 * contract added to the exposed package later inherits the same guarantee.
 * </p>
 */
class UsersExposedApiArchitectureTest {

    private static final String USERS_MODULE =
            "io.github.piresrenan.orderhub.users";

    private static final String EXPOSED_API_PACKAGE =
            "io.github.piresrenan.orderhub.users.application.port.in..";

    private static final String INTERNAL_DOMAIN_PACKAGE =
            "io.github.piresrenan.orderhub.users.domain..";

    @Test
    void exposedUsersApiDoesNotDependOnInternalUsersDomain() {
        // Why: users::api is the exposed cross-module boundary while
        // users.domain is deliberately internal, as its own package contract
        // states.
        // Covers: return types, parameter/signature dependencies and other
        // bytecode-visible dependencies from exposed Users API types to Users
        // domain types.
        // Prevents: a future cross-module consumer from inheriting a
        // non-exposed Users domain dependency and recreating the Spring
        // Modulith violation already observed in Security.

        JavaClasses usersModule = new ClassFileImporter()
                .withImportOption(
                        ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(USERS_MODULE);

        noClasses()
                .that()
                .resideInAPackage(EXPOSED_API_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(INTERNAL_DOMAIN_PACKAGE)
                .because("the exposed Users application API must carry only"
                        + " framework-neutral application contracts and opaque"
                        + " identifiers, never Users domain models")
                .check(usersModule);
    }
}

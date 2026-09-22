package io.github.piresrenan.orderhub.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders;

/** Executable guard for the only Users outbound port Security may implement. */
class SecurityUsersOutboundPortArchitectureTest {

    private static final String USERS_OUTBOUND_PORTS =
            "io.github.piresrenan.orderhub.users.application.port.out";

    @Test
    void securityDependsOnNoUsersOutboundPortExceptTheIssuerTrustSpi() {
        // Why: Users persistence ports are internal; Covers: bytecode dependencies
        // and caught exception types, which ordinary dependency analysis omits;
        // Prevents: persistence exceptions or repositories crossing into Security.
        JavaClasses security = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.piresrenan.orderhub.security");

        classes()
                .that()
                .resideInAPackage("io.github.piresrenan.orderhub.security..")
                .should(new ArchCondition<JavaClass>("reference no Users outbound port except TrustedExternalIdentityProviders") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        var dependencies = item.getDirectDependenciesFromSelf().stream()
                                .map(dependency -> dependency.getTargetClass());
                        var caught = item.getCodeUnits().stream()
                                .flatMap(unit -> unit.getTryCatchBlocks().stream())
                                .flatMap(block -> block.getCaughtThrowables().stream());
                        Stream.concat(dependencies, caught)
                                .filter(target -> target.getPackageName().startsWith(USERS_OUTBOUND_PORTS))
                                .filter(target -> !target.isEquivalentTo(TrustedExternalIdentityProviders.class))
                                .distinct()
                                .forEach(target -> events.add(SimpleConditionEvent.violated(item,
                                        item.getName() + " references " + target.getName())));
                    }
                })
                .because("Security may implement only the accepted identity-provider-trust SPI of Users")
                .check(security);
    }
}

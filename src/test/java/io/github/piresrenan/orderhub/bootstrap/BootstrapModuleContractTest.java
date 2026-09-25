package io.github.piresrenan.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.authorization.application.port.in.bootstrap.FirstOperatorPlatformAuthorityUseCase;
import io.github.piresrenan.orderhub.bootstrap.adapter.in.command.FirstOperatorBootstrapCommand;

/**
 * Why: ADR-0022 keeps the ceremony a leaf module over three named owner contracts.
 * Covers: declared dependencies, the narrow Authorization contract and the command-only exposure.
 * Prevents: silent widening into foreign modules or other modules consuming the ceremony.
 */
class BootstrapModuleContractTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(OrderHubApplication.class);

    @Test
    void bootstrapDeclaresOnlyTheThreeOwnerContracts() throws Exception {
        var declaration = Class.forName("io.github.piresrenan.orderhub.bootstrap.package-info").getAnnotation(ApplicationModule.class);
        assertThat(declaration.allowedDependencies()).containsExactlyInAnyOrder(
                "users::api", "users::identity-provider-trust", "authorization::first-operator-bootstrap");
    }

    @Test
    void onlyTheCommandAdapterIsExposedAndNoBusinessModuleDependsOnBootstrap() {
        var bootstrap = MODULES.getModuleByName("bootstrap").orElseThrow();
        assertThat(bootstrap.getNamedInterfaces().getByName("command").orElseThrow().contains(FirstOperatorBootstrapCommand.class)).isTrue();
        MODULES.stream().filter(module -> !module.equals(bootstrap)).forEach(module ->
                assertThat(module.getDirectDependencies(MODULES).contains(bootstrap)).as(module.getIdentifier().toString()).isFalse());
    }

    @Test
    void ceremonyPathNeverSuspendsOrDetachesTheCallerTransactionAndNeverWritesForeignSchemas() throws Exception {
        var base = java.nio.file.Path.of("src/main/java/io/github/piresrenan/orderhub");
        var path = new java.util.ArrayList<java.nio.file.Path>();
        try (var sources = java.nio.file.Files.walk(base.resolve("bootstrap"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(path::add);
        }
        // Every owner class the ceremony transaction passes through (ADR-0022).
        for (var owner : java.util.List.of(
                "users/application/service/ResolveOrCreateExternalUserService.java",
                "users/application/service/CreateUserService.java",
                "users/application/service/BindExternalIdentityService.java",
                "users/application/service/ResolveExternalIdentityService.java",
                "users/adapter/out/persistence/postgresql/PostgreSqlExternalIdentitySerializationCoordinator.java",
                "users/adapter/out/persistence/postgresql/PostgreSqlUserRepository.java",
                "users/adapter/out/persistence/postgresql/PostgreSqlExternalIdentityBindingRepository.java",
                "authorization/application/service/FirstOperatorPlatformAuthorityService.java",
                "authorization/adapter/out/persistence/postgresql/PostgreSqlFirstOperatorAuthorityRepository.java",
                "authorization/adapter/out/persistence/postgresql/PostgreSqlAdministrativeGrantRepository.java")) {
            path.add(base.resolve(owner));
        }
        for (var file : path) {
            var text = java.nio.file.Files.readString(file);
            assertThat(text).as(file.toString()).doesNotContain("REQUIRES_NEW", "NOT_SUPPORTED", "PROPAGATION_NEVER");
            if (file.startsWith(base.resolve("bootstrap"))) {
                assertThat(text).as(file.toString()).doesNotContainPattern("(?i)(FROM|INTO|UPDATE|JOIN|TABLE)\\s+(users|access_control)\\.");
                assertThat(text).as(file.toString()).doesNotContain("@RestController",
                        "@Controller", "Mapping(", "ApplicationRunner", "CommandLineRunner", "@Scheduled");
            }
        }
    }

    @Test
    void authorizationFirstOperatorContractIsASeparateNarrowNamedInterface() {
        var authorization = MODULES.getModuleByName("authorization").orElseThrow();
        var contract = authorization.getNamedInterfaces().getByName("first-operator-bootstrap").orElseThrow();
        assertThat(contract.contains(FirstOperatorPlatformAuthorityUseCase.class)).isTrue();
        assertThat(Arrays.stream(FirstOperatorPlatformAuthorityUseCase.class.getMethods()).map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("platformAuthorityExists", "establishFirstOperatorAuthority");
        assertThat(authorization.getNamedInterfaces().getByName("administration").orElseThrow()
                .contains(FirstOperatorPlatformAuthorityUseCase.class)).isFalse();
    }
}

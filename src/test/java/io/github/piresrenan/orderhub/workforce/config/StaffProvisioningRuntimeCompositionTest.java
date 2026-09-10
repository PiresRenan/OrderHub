package io.github.piresrenan.orderhub.workforce.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.authorization.application.port.in.current.AuthorizeCurrentTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningIssuanceService;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;

class StaffProvisioningRuntimeCompositionTest {

    private static final String TTL_PROPERTY =
            "orderhub.workforce.staff-provisioning.intent-ttl";

    @Test
    void explicitValidTtlComposesExactlyOneRepositoryAndIssuanceUseCase() {

        try (var context =
                contextWithTtl(
                        "30m")) {

            context.refresh();
            assertThat(context.getBeansOfType(ConsumeStaffProvisioningUseCase.class).values())
                    .singleElement().isNotNull();
            assertThat(context.getBeansOfType(io.github.piresrenan.orderhub.workforce.application.port.in.ManageStaffProvisioningUseCase.class).values())
                    .singleElement().isNotNull();

            var repositories =
                    context.getBeansOfType(
                            StaffProvisioningIntentRepository.class);

            var useCases =
                    context.getBeansOfType(
                            IssueStaffProvisioningIntentUseCase.class);

            if (repositories.size() != 1
                    || useCases.size() != 1) {

                throw new AssertionError(
                        "Workforce Staff provisioning runtime composition is missing");
            }

            assertThat(
                    repositories.values())
                    .singleElement()
                    .isNotNull();

            assertThat(
                    useCases.values())
                    .singleElement()
                    .isInstanceOf(
                            StaffProvisioningIssuanceService.class);
        }
    }

    @Test
    void missingTtlFailsClosedInsteadOfInventingRuntimeDefault() {

        try (var context =
                contextWithoutTtl()) {

            var failure =
                    refreshFailure(
                            context);

            if (failure == null) {
                throw new AssertionError(
                        "Workforce Staff provisioning TTL required configuration is missing");
            }
        }
    }

    @Test
    void nonPositiveTtlFailsClosedDuringRuntimeComposition() {

        for (var invalidValue : new String[] {
                "0s",
                "-1s"
        }) {

            try (var context =
                    contextWithTtl(
                            invalidValue)) {

                var failure =
                        refreshFailure(
                                context);

                if (failure == null) {
                    throw new AssertionError(
                            "Workforce Staff provisioning TTL positive validation is missing");
                }
            }
        }
    }

    private AnnotationConfigApplicationContext contextWithTtl(
            String ttl) {

        var context =
                baseContext();

        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "staff-provisioning-runtime-test",
                                Map.of(
                                        TTL_PROPERTY,
                                        ttl)));

        return context;
    }

    private AnnotationConfigApplicationContext contextWithoutTtl() {

        return baseContext();
    }

    private AnnotationConfigApplicationContext baseContext() {

        var context =
                new AnnotationConfigApplicationContext();

        context.registerBean(StaffProvisioningAuthorizationUseCase.class,
                () -> unsupportedProxy(StaffProvisioningAuthorizationUseCase.class));
        context.registerBean(IsTenantMembershipOperationallyActiveUseCase.class,
                () -> unsupportedProxy(IsTenantMembershipOperationallyActiveUseCase.class));
        context.registerBean(ResolveOrCreateExternalUserUseCase.class,
                () -> unsupportedProxy(ResolveOrCreateExternalUserUseCase.class));
        context.registerBean(EnsureActiveTenantMembershipUseCase.class,
                () -> unsupportedProxy(EnsureActiveTenantMembershipUseCase.class));
        context.registerBean(FindTenantOperationalStateUseCase.class,
                () -> unsupportedProxy(FindTenantOperationalStateUseCase.class));

        context.registerBean(
                JdbcTemplate.class,
                () -> new JdbcTemplate(
                        unsupportedProxy(
                                DataSource.class)));

        context.registerBean(
                PlatformTransactionManager.class,
                () -> unsupportedProxy(
                        PlatformTransactionManager.class));

        context.registerBean(
                AuthorizeCurrentTenantActionUseCase.class,
                () -> unsupportedProxy(
                        AuthorizeCurrentTenantActionUseCase.class));

        context.register(
                WorkforceConfiguration.class);

        return context;
    }

    private Throwable refreshFailure(
            AnnotationConfigApplicationContext context) {

        try {

            context.refresh();

            return null;

        } catch (Throwable failure) {

            return failure;
        }
    }

    private <T> T unsupportedProxy(
            Class<T> type) {

        var proxy =
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {
                                type
                        },
                        (instance, method, arguments) -> {

                            if (method.getDeclaringClass()
                                    .equals(
                                            Object.class)) {

                                return switch (method.getName()) {
                                    case "toString" ->
                                            "synthetic-"
                                                    + type.getSimpleName();

                                    case "hashCode" ->
                                            System.identityHashCode(
                                                    instance);

                                    case "equals" ->
                                            instance == arguments[0];

                                    default ->
                                            throw new AssertionError(
                                                    "Unsupported Object method: "
                                                            + method.getName());
                                };
                            }

                            throw new UnsupportedOperationException(
                                    "Runtime composition fixture must not invoke "
                                            + type.getSimpleName()
                                            + "."
                                            + method.getName());
                        });

        return type.cast(
                proxy);
    }
}

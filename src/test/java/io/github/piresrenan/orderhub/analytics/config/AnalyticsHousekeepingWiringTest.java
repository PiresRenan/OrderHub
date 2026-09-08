package io.github.piresrenan.orderhub.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.github.piresrenan.orderhub.analytics.adapter.in.scheduling.AnalyticsHousekeepingTrigger;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeFactRetentionService;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionService;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;

class AnalyticsHousekeepingWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            AnalyticsConfiguration.class,
                            Dependencies.class)
                    .withPropertyValues(
                            "orderhub.analytics.housekeeping.batch-size=100",
                            "orderhub.analytics.housekeeping.fixed-delay=1h");

    @Test
    void disabledModeCreatesNoRetentionOrTriggerBean() {
        contextRunner.withPropertyValues(
                        "orderhub.analytics.housekeeping.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            WorkforceAuthorityChangeProjectionService.class);
                    assertThat(context).doesNotHaveBean(
                            WorkforceAuthorityChangeFactRetentionService.class);
                    assertThat(context).doesNotHaveBean(
                            AnalyticsHousekeepingTrigger.class);
                });
    }

    @Test
    void enabledInvalidModeFailsBeforeOperationalBeansCanRun() {
        contextRunner.withPropertyValues(
                        "orderhub.analytics.housekeeping.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("retentionWindowValid");
                });
    }

    @Test
    void enabledValidModeWiresOneBoundedTrigger() {
        contextRunner.withPropertyValues(
                        "orderhub.analytics.housekeeping.enabled=true",
                        "orderhub.analytics.housekeeping.retention-window=90d")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            WorkforceAuthorityChangeFactRetentionService.class);
                    assertThat(context).hasSingleBean(
                            AnalyticsHousekeepingTrigger.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase sourceResolver() {
            return mock(ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}

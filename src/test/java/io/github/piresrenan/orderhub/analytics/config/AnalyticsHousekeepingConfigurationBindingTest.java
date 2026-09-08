package io.github.piresrenan.orderhub.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

class AnalyticsHousekeepingConfigurationBindingTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(BindingConfiguration.class);

    @Test
    void missingFixedDelayFailsThroughValidationWithoutNullPointerException() {
        contextRunner
                .withPropertyValues(
                        "orderhub.analytics.housekeeping.enabled=true",
                        "orderhub.analytics.housekeeping.retention-window=90d",
                        "orderhub.analytics.housekeeping.batch-size=100")
                .run(context -> {
                    assertThat(context).hasFailed();
                    var failure = context.getStartupFailure();
                    assertThat(failure)
                            .hasStackTraceContaining("fixedDelayValid");
                    assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                            .isNotInstanceOf(NullPointerException.class);
                });
    }

    @Test
    void disabledModeAllowsAnAbsentRetentionWindow() {
        contextRunner.withPropertyValues(
                        "orderhub.analytics.housekeeping.enabled=false",
                        "orderhub.analytics.housekeeping.batch-size=1",
                        "orderhub.analytics.housekeeping.fixed-delay=1s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            AnalyticsHousekeepingProperties.class)
                            .retentionWindow()).isNull();
                });
    }

    @Test
    void enabledModeRequiresRetentionWindowDuringBinding() {
        validBase().run(context -> assertThat(context).hasFailed());
    }

    @Test
    void validatesBatchAndDelayBoundariesDuringBinding() {
        for (var invalid : new String[] {
                "orderhub.analytics.housekeeping.batch-size=0",
                "orderhub.analytics.housekeeping.batch-size=-1",
                "orderhub.analytics.housekeeping.batch-size=1001",
                "orderhub.analytics.housekeeping.fixed-delay=0s",
                "orderhub.analytics.housekeeping.fixed-delay=-1s"}) {
            validBaseWithRetention().withPropertyValues(invalid)
                    .run(context -> assertThat(context).hasFailed());
        }

        for (var valid : new String[] {
                "orderhub.analytics.housekeeping.batch-size=1",
                "orderhub.analytics.housekeeping.batch-size=1000"}) {
            validBaseWithRetention().withPropertyValues(valid)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    private ApplicationContextRunner validBase() {
        return contextRunner.withPropertyValues(
                "orderhub.analytics.housekeeping.enabled=true",
                "orderhub.analytics.housekeeping.batch-size=100",
                "orderhub.analytics.housekeeping.fixed-delay=1h");
    }

    private ApplicationContextRunner validBaseWithRetention() {
        return validBase().withPropertyValues(
                "orderhub.analytics.housekeeping.retention-window=90d");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AnalyticsHousekeepingProperties.class)
    static class BindingConfiguration {
    }
}

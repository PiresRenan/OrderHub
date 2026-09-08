package io.github.piresrenan.orderhub.analytics.config;

import java.time.Clock;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.piresrenan.orderhub.analytics.adapter.in.event.spring.WorkforceAuthorityChangeAuditRecordedListener;
import io.github.piresrenan.orderhub.analytics.adapter.in.scheduling.AnalyticsHousekeepingTrigger;
import io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql.PostgreSqlAnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuthorityChangeFactRetentionRepository;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeFactRetentionService;
import io.github.piresrenan.orderhub.analytics.application.port.out.AnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionService;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalFactType;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalRetentionPolicy;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalRetentionPolicyCatalog;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;

/**
 * Composition root for analytical ingestion.
 *
 * <p>
 * Analytical retention remains disabled by default. Enabling it requires an
 * explicit positive deployment-supplied window; the same policy then governs
 * both bounded cleanup and rejection of already-expired replay.
 * </p>
 *
 * <p>
 * Asynchronous annotation processing is enabled because the selected Spring
 * Modulith listener semantics depend on it: without it the after-commit
 * projection would execute on the publishing thread, so an analytical failure
 * would surface to the operational caller whose transaction has already
 * committed. No executor is defined or overridden here; the application's own
 * task executor remains in effect.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AnalyticsHousekeepingProperties.class)
public class AnalyticsConfiguration {

    @Bean
    Clock analyticsClock() {
        return Clock.systemUTC();
    }

    @Bean
    AnalyticalSubjectPseudonymRepository analyticalSubjectPseudonymRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlAnalyticalSubjectPseudonymRepository(
                jdbcTemplate);
    }

    @Bean
    WorkforceAuthorityChangeFactRepository
            workforceAuthorityChangeFactRepository(
                    JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforceAuthorityChangeFactRepository(
                jdbcTemplate);
    }

    @Bean
    WorkforceAuthorityChangeProjectionService
            workforceAuthorityChangeProjectionService(
                    ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase
                            sourceResolver,
                    AnalyticalSubjectPseudonymRepository pseudonymRepository,
                    WorkforceAuthorityChangeFactRepository factRepository,
                    AnalyticsHousekeepingProperties properties,
                    Clock analyticsClock) {

        if (!properties.enabled()) {
            return new WorkforceAuthorityChangeProjectionService(
                    sourceResolver, pseudonymRepository, factRepository);
        }

        return new WorkforceAuthorityChangeProjectionService(
                sourceResolver, pseudonymRepository, factRepository,
                retentionPolicies(properties), analyticsClock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "orderhub.analytics.housekeeping",
            name = "enabled", havingValue = "true")
    WorkforceAuthorityChangeFactRetentionService retentionService(
            JdbcTemplate jdbcTemplate,
            AnalyticsHousekeepingProperties properties) {
        return new WorkforceAuthorityChangeFactRetentionService(
                retentionPolicies(properties),
                new PostgreSqlWorkforceAuthorityChangeFactRetentionRepository(
                        jdbcTemplate));
    }

    @Bean
    @ConditionalOnProperty(prefix = "orderhub.analytics.housekeeping",
            name = "enabled", havingValue = "true")
    AnalyticsHousekeepingTrigger analyticsHousekeepingTrigger(
            WorkforceAuthorityChangeFactRetentionService retentionService,
            AnalyticsHousekeepingProperties properties,
            Clock analyticsClock,
            MeterRegistry meterRegistry) {
        return new AnalyticsHousekeepingTrigger(
                retentionService, properties, analyticsClock, meterRegistry);
    }

    private static AnalyticalRetentionPolicyCatalog retentionPolicies(
            AnalyticsHousekeepingProperties properties) {
        return new AnalyticalRetentionPolicyCatalog(Map.of(
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE,
                new AnalyticalRetentionPolicy(properties.retentionWindow())));
    }

    @Bean
    WorkforceAuthorityChangeAuditRecordedListener
            workforceAuthorityChangeAuditRecordedListener(
                    WorkforceAuthorityChangeProjectionService projectionService,
                    MeterRegistry meterRegistry) {

        return new WorkforceAuthorityChangeAuditRecordedListener(
                projectionService,
                meterRegistry);
    }
}

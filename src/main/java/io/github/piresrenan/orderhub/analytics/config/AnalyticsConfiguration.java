package io.github.piresrenan.orderhub.analytics.config;

import java.time.Clock;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
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

    /** Supplies one UTC clock for ingestion eligibility and cleanup cutoff. */
    @Bean
    Clock analyticsClock() {
        return Clock.systemUTC();
    }

    /** Composes the analytics-owned Tenant-local pseudonym persistence. */
    @Bean
    AnalyticalSubjectPseudonymRepository analyticalSubjectPseudonymRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlAnalyticalSubjectPseudonymRepository(
                jdbcTemplate);
    }

    /** Composes idempotent persistence for the admitted workforce fact type. */
    @Bean
    WorkforceAuthorityChangeFactRepository
            workforceAuthorityChangeFactRepository(
                    JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforceAuthorityChangeFactRepository(
                jdbcTemplate);
    }

    /** Applies expiry before projection only when the same cleanup policy is on. */
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

    /** Composes the owner retention operation only when its policy is enabled. */
    @Bean
    @Conditional(HousekeepingEnabled.class)
    WorkforceAuthorityChangeFactRetentionService retentionService(
            JdbcTemplate jdbcTemplate,
            AnalyticsHousekeepingProperties properties) {
        return new WorkforceAuthorityChangeFactRetentionService(
                retentionPolicies(properties),
                new PostgreSqlWorkforceAuthorityChangeFactRetentionRepository(
                        jdbcTemplate));
    }

    /** Schedules one bounded operation using the same policy as ingestion. */
    @Bean
    @Conditional(HousekeepingEnabled.class)
    AnalyticsHousekeepingTrigger analyticsHousekeepingTrigger(
            WorkforceAuthorityChangeFactRetentionService retentionService,
            AnalyticsHousekeepingProperties properties,
            Clock analyticsClock,
            MeterRegistry meterRegistry) {
        return new AnalyticsHousekeepingTrigger(
                retentionService, properties, analyticsClock, meterRegistry);
    }

    /** Builds the sole admitted dataset policy from validated operator input. */
    private static AnalyticalRetentionPolicyCatalog retentionPolicies(
            AnalyticsHousekeepingProperties properties) {
        return new AnalyticalRetentionPolicyCatalog(Map.of(
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE,
                new AnalyticalRetentionPolicy(properties.retentionWindow())));
    }

    /** Uses Spring boolean conversion consistently with configuration binding. */
    static final class HousekeepingEnabled implements Condition {

        /** Keeps boolean aliases from enabling ingestion without cleanup. */
        @Override
        public boolean matches(
                ConditionContext context,
                AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty(
                    "orderhub.analytics.housekeeping.enabled",
                    Boolean.class, false);
        }
    }

    /** Connects durable after-commit publications to the owner projection service. */
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

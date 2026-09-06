package io.github.piresrenan.orderhub.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.piresrenan.orderhub.analytics.adapter.in.event.spring.WorkforceAuthorityChangeAuditRecordedListener;
import io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql.PostgreSqlAnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.AnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionService;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;

/**
 * Composition root for analytical ingestion.
 *
 * <p>
 * Analytical retention is deliberately not wired here. Its effective window is
 * a configuration decision ADR-0014 leaves open, so wiring a policy catalog now
 * would fix that decision as a side effect of enabling ingestion.
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
public class AnalyticsConfiguration {

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
                    WorkforceAuthorityChangeFactRepository factRepository) {

        return new WorkforceAuthorityChangeProjectionService(
                sourceResolver,
                pseudonymRepository,
                factRepository);
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

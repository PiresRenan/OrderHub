package io.github.piresrenan.orderhub.workforce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.transaction.spring.SpringWorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.AuditedWorkforceMutationService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedWorkforceMutationAuthorizationService;

@Configuration(proxyBeanMethods = false)
public class WorkforceConfiguration {

    @Bean
    WorkforceAuditRepository workforceAuditRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforceAuditRepository(
                jdbcTemplate);
    }

    @Bean
    WorkforcePositionChangeRepository workforcePositionChangeRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforcePositionChangeRepository(
                jdbcTemplate);
    }

    @Bean
    WorkforceTransactionExecutor workforceTransactionExecutor(
            PlatformTransactionManager transactionManager) {

        var transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        return new SpringWorkforceTransactionExecutor(
                transactionTemplate);
    }

    @Bean
    PrivilegedWorkforceMutationAuthorizationService
            privilegedWorkforceMutationAuthorizationService() {

        return new PrivilegedWorkforceMutationAuthorizationService();
    }

    @Bean
    AuditedWorkforceMutationService auditedWorkforceMutationService(
            WorkforceTransactionExecutor transactionExecutor,
            WorkforceAuditRepository auditRepository) {

        return new AuditedWorkforceMutationService(
                transactionExecutor,
                auditRepository);
    }

    @Bean
    PrivilegedPositionChangeExecutionService
            privilegedPositionChangeExecutionService(
                    WorkforceTransactionExecutor transactionExecutor,
                    WorkforcePositionChangeRepository positionRepository,
                    WorkforceAuditRepository auditRepository,
                    PrivilegedWorkforceMutationAuthorizationService authorizationService) {

        return new PrivilegedPositionChangeExecutionService(
                transactionExecutor,
                positionRepository,
                auditRepository,
                authorizationService);
    }
}

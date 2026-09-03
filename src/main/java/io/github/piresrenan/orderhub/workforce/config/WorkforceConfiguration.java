package io.github.piresrenan.orderhub.workforce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.transaction.spring.SpringWorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.AuditedWorkforceMutationService;

@Configuration(proxyBeanMethods = false)
public class WorkforceConfiguration {

    @Bean
    WorkforceAuditRepository workforceAuditRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforceAuditRepository(
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
    AuditedWorkforceMutationService auditedWorkforceMutationService(
            WorkforceTransactionExecutor transactionExecutor,
            WorkforceAuditRepository auditRepository) {

        return new AuditedWorkforceMutationService(
                transactionExecutor,
                auditRepository);
    }
}

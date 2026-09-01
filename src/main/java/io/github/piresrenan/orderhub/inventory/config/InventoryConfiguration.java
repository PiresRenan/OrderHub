package io.github.piresrenan.orderhub.inventory.config;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.PostgreSqlInventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.PostgreSqlInventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.PostgreSqlInventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentIdGenerator;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryTimeProvider;
import io.github.piresrenan.orderhub.inventory.application.service.CommitOrderInventoryService;

@Configuration(proxyBeanMethods = false)
public class InventoryConfiguration {

    @Bean
    InventoryPolicyRepository inventoryPolicyRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlInventoryPolicyRepository(
                jdbcTemplate);
    }

    @Bean
    InventoryPositionRepository inventoryPositionRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlInventoryPositionRepository(
                jdbcTemplate);
    }

    @Bean
    InventoryCommitmentRepository inventoryCommitmentRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlInventoryCommitmentRepository(
                jdbcTemplate);
    }

    @Bean
    InventoryCommitmentIdGenerator inventoryCommitmentIdGenerator() {

        return UUID::randomUUID;
    }

    @Bean
    InventoryTimeProvider inventoryTimeProvider() {

        return Instant::now;
    }

    @Bean
    CommitOrderInventoryUseCase commitOrderInventoryUseCase(
            InventoryPolicyRepository policyRepository,
            InventoryPositionRepository positionRepository,
            InventoryCommitmentRepository commitmentRepository,
            InventoryCommitmentIdGenerator commitmentIdGenerator,
            InventoryTimeProvider timeProvider) {

        return new CommitOrderInventoryService(
                policyRepository,
                positionRepository,
                commitmentRepository,
                commitmentIdGenerator,
                timeProvider);
    }
}
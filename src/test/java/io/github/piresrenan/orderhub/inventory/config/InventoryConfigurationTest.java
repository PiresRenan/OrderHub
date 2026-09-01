package io.github.piresrenan.orderhub.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.PostgreSqlInventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.PostgreSqlInventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.PostgreSqlInventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.application.service.CommitOrderInventoryService;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class InventoryConfigurationTest {

    @Autowired
    private CommitOrderInventoryUseCase commitOrderInventoryUseCase;

    @Autowired
    private InventoryPolicyRepository policyRepository;

    @Autowired
    private InventoryPositionRepository positionRepository;

    @Autowired
    private InventoryCommitmentRepository commitmentRepository;

    @Test
    void wiresInventoryCommitmentApplicationBoundary() {

        assertThat(commitOrderInventoryUseCase)
                .isInstanceOf(
                        CommitOrderInventoryService.class);

        assertThat(policyRepository)
                .isInstanceOf(
                        PostgreSqlInventoryPolicyRepository.class);

        assertThat(positionRepository)
                .isInstanceOf(
                        PostgreSqlInventoryPositionRepository.class);

        assertThat(commitmentRepository)
                .isInstanceOf(
                        PostgreSqlInventoryCommitmentRepository.class);
    }
}
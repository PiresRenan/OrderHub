package io.github.piresrenan.orderhub.tenants.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantRepository;
import io.github.piresrenan.orderhub.tenants.application.port.in.CreateTenantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantIdGenerator;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class TenantsConfigurationTest {

    @Autowired
    private CreateTenantUseCase createTenantUseCase;

    @Autowired
    private TenantIdGenerator tenantIdGenerator;

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void wiresTenantApplicationComponents() {
        // Why: domain and persistence implementations may be correct independently
        // while runtime composition still fails because required beans are absent or
        // wired to the wrong implementation.
        // Covers: Spring composition of the Tenant use case, identity generator and
        // durable repository.
        // Prevents: runtime startup succeeding without an executable Tenant creation
        // use case or accidentally replacing the PostgreSQL adapter.

        assertThat(createTenantUseCase)
                .isNotNull();

        assertThat(tenantIdGenerator)
                .isNotNull();

        assertThat(tenantRepository)
                .isInstanceOf(PostgreSqlTenantRepository.class);
    }
}

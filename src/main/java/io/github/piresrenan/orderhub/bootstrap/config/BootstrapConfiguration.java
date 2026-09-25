package io.github.piresrenan.orderhub.bootstrap.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.authorization.application.port.in.bootstrap.FirstOperatorPlatformAuthorityUseCase;
import io.github.piresrenan.orderhub.bootstrap.adapter.out.persistence.postgresql.PostgreSqlFirstOperatorCeremonyRepository;
import io.github.piresrenan.orderhub.bootstrap.adapter.out.transaction.spring.SpringFirstOperatorBootstrapTransaction;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.BootstrapFirstOperatorUseCase;
import io.github.piresrenan.orderhub.bootstrap.application.service.FirstOperatorBootstrapService;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishNewExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders;

/**
 * Composes the ceremony use case. Composition alone performs no bootstrap:
 * only the offline command adapter invokes it, and no web mapping exists.
 */
@Configuration(proxyBeanMethods = false)
public class BootstrapConfiguration {

    /**
     * Wires the ceremony over owner APIs and the shared transaction manager.
     *
     * @param trust     configured trusted issuer set shared with JWT verification
     * @param manager   the single application transaction manager
     * @param jdbc      shared JDBC access for bootstrap-owned relations
     * @param resolver  Users exact identity resolution
     * @param users     Users exclusive User and binding establishment
     * @param authority Authorization first-operator grant
     * @return the ceremony use case
     */
    @Bean
    BootstrapFirstOperatorUseCase bootstrapFirstOperatorUseCase(TrustedExternalIdentityProviders trust,
            PlatformTransactionManager manager, JdbcTemplate jdbc, ResolveExternalIdentityUseCase resolver,
            EstablishNewExternalUserUseCase users, FirstOperatorPlatformAuthorityUseCase authority) {
        return new FirstOperatorBootstrapService(trust, new SpringFirstOperatorBootstrapTransaction(manager),
                new PostgreSqlFirstOperatorCeremonyRepository(jdbc), resolver, users, authority, UUID::randomUUID);
    }
}

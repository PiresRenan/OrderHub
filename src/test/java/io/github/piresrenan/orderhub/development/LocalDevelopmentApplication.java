package io.github.piresrenan.orderhub.development;

import java.util.Map;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.OrderHubApplication;

/** Explicit test-classpath launcher; no runtime profile can add this class to the production artifact. */
public final class LocalDevelopmentApplication {
    private static final String POSTGRES = "postgres:18.6-trixie@sha256:"
            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280";

    private LocalDevelopmentApplication() { }

    /** Starts the documented loopback services with a newly owned disposable database. */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("The disposable development launcher accepts no runtime overrides");
        }
        start(8080, 9090);
    }

    /** Port zero is reserved for acceptance tests; all interfaces and database ownership remain fixed. */
    public static ConfigurableApplicationContext start(int applicationPort, int issuerPort) throws Exception {
        if (applicationPort < 0 || applicationPort > 65535 || issuerPort < 0 || issuerPort > 65535) {
            throw new IllegalArgumentException("Invalid development port");
        }
        var database = new PostgreSQLContainer(DockerImageName.parse(POSTGRES).asCompatibleSubstituteFor("postgres"));
        database.withDatabaseName("orderhub_development")
                .withUsername("orderhub_development")
                .withPassword("synthetic-disposable-development-password");
        DevelopmentIssuer issuer = null;
        try {
            database.start();
            issuer = new DevelopmentIssuer(issuerPort);
            var ownedIssuer = issuer;
            var application = new SpringApplication(OrderHubApplication.class, DevelopmentConfiguration.class);
            application.setAdditionalProfiles("dev");
            application.addInitializers(context -> {
                // First priority prevents ambient datasource/JWT settings from redirecting a fixture write.
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ownedDevelopmentRuntime", Map.ofEntries(
                        Map.entry("server.address", "127.0.0.1"),
                        Map.entry("server.port", applicationPort),
                        Map.entry("spring.datasource.url", database.getJdbcUrl()),
                        Map.entry("spring.datasource.username", database.getUsername()),
                        Map.entry("spring.datasource.password", database.getPassword()),
                        Map.entry("spring.flyway.url", database.getJdbcUrl()),
                        Map.entry("spring.flyway.user", database.getUsername()),
                        Map.entry("spring.flyway.password", database.getPassword()),
                        Map.entry("spring.flyway.enabled", true),
                        Map.entry("springdoc.api-docs.enabled", true),
                        Map.entry("springdoc.swagger-ui.enabled", true),
                        Map.entry("orderhub.security.jwt.issuer", ownedIssuer.baseUri()),
                        Map.entry("orderhub.security.jwt.audience", DevelopmentIssuer.AUDIENCE),
                        Map.entry("orderhub.security.jwt.token-profile", "GENERIC"),
                        Map.entry("orderhub.security.jwt.jwk-set-uri", ownedIssuer.baseUri() + "/jwks"))));
                context.getEnvironment().setActiveProfiles("dev");
                var beans = (DefaultListableBeanFactory) context.getBeanFactory();
                beans.registerSingleton("developmentIssuer", ownedIssuer);
                beans.registerSingleton("developmentDatabase", database);
                beans.registerDisposableBean("developmentInfrastructure", () -> {
                    ownedIssuer.close();
                    database.stop();
                });
            });
            return application.run();
        } catch (Exception | Error failure) {
            if (issuer != null) issuer.close();
            database.stop();
            throw failure;
        }
    }
}

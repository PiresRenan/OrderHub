package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.*;

/** Why: deployed aliases must work; Covers: actual environment binding; Prevents: inert trust settings. */
class ReleaseSecurityEnvironmentTest {
    @Test void deploymentEnvironmentSelectsCognitoAndExactOrigins() {
        new ApplicationContextRunner().withUserConfiguration(Properties.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "ORDERHUB_SECURITY_JWT_ISSUER", "https://issuer.example.test",
                                "ORDERHUB_SECURITY_JWT_AUDIENCE", "https://api.example.test",
                                "ORDERHUB_SECURITY_JWT_JWK_SET_URI", "https://issuer.example.test/jwks",
                                "ORDERHUB_SECURITY_JWT_TOKEN_PROFILE", "COGNITO",
                                "ORDERHUB_SECURITY_CORS_ALLOWED_ORIGINS", "https://frontend.example.test,http://localhost:5173",
                                "ORDERHUB_SECURITY_JWT_ADDITIONALISSUERS_0_ISSUER", "https://other.example.test",
                                "ORDERHUB_SECURITY_JWT_ADDITIONALISSUERS_0_JWKSETURI", "https://other.example.test/jwks",
                                "ORDERHUB_SECURITY_JWT_ADDITIONALISSUERS_0_TOKENPROFILE", "COGNITO"))))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtResourceServerProperties.class).tokenProfile()).isEqualTo(JwtTokenProfile.COGNITO);
                    assertThat(context.getBean(BrowserCorsProperties.class).allowedOrigins())
                            .containsExactly("https://frontend.example.test", "http://localhost:5173");
                    assertThat(context.getBean(AdditionalJwtTrustProperties.class).additionalIssuers().getFirst().tokenProfile())
                            .isEqualTo(JwtTokenProfile.COGNITO);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({JwtResourceServerProperties.class, AdditionalJwtTrustProperties.class, BrowserCorsProperties.class})
    static class Properties { }
}

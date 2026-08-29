package io.github.piresrenan.orderhub;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OrderHubApplicationTests {

    @Test
    void contextLoads() {
        // Why: configuration errors can make the application impossible to start even
        // when isolated unit tests remain green.
        // Covers: creation of the complete Spring ApplicationContext.
        // Prevents: missing beans, incompatible configuration and startup regressions.
    }
}

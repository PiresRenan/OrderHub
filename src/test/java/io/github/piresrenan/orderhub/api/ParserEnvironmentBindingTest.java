package io.github.piresrenan.orderhub.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import tools.jackson.databind.json.JsonMapper;

/** Why: deployed limits were inert; Covers: effective Jackson factory; Prevents: configuration that only looks enforced. */
@SpringBootTest(properties = {"ORDERHUB_JSON_MAX_DOCUMENT_LENGTH=4096", "ORDERHUB_JSON_MAX_NESTING_DEPTH=8", "ORDERHUB_JSON_MAX_TOKEN_COUNT=100"})
@Import(PostgreSqlTestConfiguration.class)
@TestPropertySource(properties = "orderhub.security.jwt.token-profile=GENERIC")
class ParserEnvironmentBindingTest {
    @Autowired JsonMapper mapper;
    @Test
    void deploymentVariablesReachTheActualParser() {
        var constraints = mapper.tokenStreamFactory().streamReadConstraints();
        assertThat(constraints.getMaxDocumentLength()).isEqualTo(4096);
        assertThat(constraints.getMaxNestingDepth()).isEqualTo(8);
        assertThat(constraints.getMaxTokenCount()).isEqualTo(100);
    }
}

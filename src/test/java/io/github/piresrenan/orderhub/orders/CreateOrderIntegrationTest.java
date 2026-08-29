package io.github.piresrenan.orderhub.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class CreateOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsOrderThroughCompleteApplicationStack() throws Exception {
        // Why: isolated unit tests cannot prove that real Spring wiring works together.
        // Covers: HTTP -> controller -> use case -> service -> domain -> repository adapter.
        // Prevents: false-positive green suites caused by mocks hiding integration defects.

        mockMvc.perform(post("/orders")
                        .header(
                                "X-Tenant-Id",
                                "11111111-1111-1111-1111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId":
                                    "22222222-2222-2222-2222-222222222222",
                                  "items": [{
                                    "productId":
                                      "33333333-3333-3333-3333-333333333333",
                                    "quantity": 2
                                  }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.tenantId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.customerId")
                        .value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.status")
                        .value("CREATED"));
    }
}
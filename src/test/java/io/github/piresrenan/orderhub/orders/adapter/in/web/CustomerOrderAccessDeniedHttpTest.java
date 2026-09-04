package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderAccessDeniedException;

class CustomerOrderAccessDeniedHttpTest {

    @Test
    void mapsCustomerOrderAccessDeniedToPrivacySafeForbiddenProblem()
            throws Exception {

        var mockMvc =
                MockMvcBuilders
                        .standaloneSetup(
                                new DeniedCustomerOrderController())
                        .setControllerAdvice(
                                new ApiExceptionHandler())
                        .build();

        var response =
                mockMvc.perform(
                                post(
                                        "/__red17/customer-orders")
                                        .accept(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(
                                status().isForbidden())
                        .andExpect(
                                content().contentTypeCompatibleWith(
                                        MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(
                                jsonPath("$.type")
                                        .value(
                                                "urn:orderhub:problem:customer-order-access-denied"))
                        .andExpect(
                                jsonPath("$.title")
                                        .value(
                                                "Customer order access denied"))
                        .andExpect(
                                jsonPath("$.status")
                                        .value(
                                                403))
                        .andExpect(
                                jsonPath("$.detail")
                                        .value(
                                                "The requested customer order operation is not permitted."))
                        .andExpect(
                                jsonPath("$.code")
                                        .value(
                                                "CUSTOMER_ORDER_ACCESS_DENIED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response)
                .doesNotContain(
                        "Customer order access denied.",
                        "binding",
                        "RESOURCE_OWNER",
                        "customerId",
                        "tenantId",
                        "userId");
    }

    @RestController
    @RequestMapping("/__red17/customer-orders")
    static final class DeniedCustomerOrderController {

        @PostMapping
        public void create() {

            throw new CustomerOrderAccessDeniedException();
        }
    }
}

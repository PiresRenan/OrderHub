package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderUnavailableException;

class CustomerOrderUnavailableHttpTest {

    @Test
    void mapsUnavailableCustomerOrderToNonEnumeratingNotFoundProblem()
            throws Exception {

        var requestedOrderId =
                UUID.randomUUID();

        var mockMvc =
                MockMvcBuilders
                        .standaloneSetup(
                                new UnavailableCustomerOrderController())
                        .setControllerAdvice(
                                new ApiExceptionHandler())
                        .build();

        var response =
                mockMvc.perform(
                                get(
                                        "/__red21/orders/{orderId}",
                                        requestedOrderId)
                                        .accept(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(
                                status().isNotFound())
                        .andExpect(
                                content().contentTypeCompatibleWith(
                                        MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(
                                jsonPath("$.type")
                                        .value(
                                                "urn:orderhub:problem:order-not-found"))
                        .andExpect(
                                jsonPath("$.title")
                                        .value(
                                                "Order not found"))
                        .andExpect(
                                jsonPath("$.status")
                                        .value(
                                                404))
                        .andExpect(
                                jsonPath("$.detail")
                                        .value(
                                                "The requested order could not be found."))
                        .andExpect(
                                jsonPath("$.code")
                                        .value(
                                                "ORDER_NOT_FOUND"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Spring populates RFC 9457 "instance" from the current request path.
        // Echoing the caller-supplied Order selector does not reveal whether the
        // requested Order is absent or inaccessible.
        assertThat(response)
                .doesNotContain(
                        "Customer order is unavailable.",
                        "Customer",
                        "customer",
                        "binding",
                        "RESOURCE_OWNER",
                        "tenantId",
                        "userId",
                        "authorization",
                        "denied",
                        "forbidden");
    }

    @RestController
    @RequestMapping("/__red21/orders")
    static final class UnavailableCustomerOrderController {

        @GetMapping("/{orderId}")
        public void view() {

            throw new CustomerOrderUnavailableException();
        }
    }
}

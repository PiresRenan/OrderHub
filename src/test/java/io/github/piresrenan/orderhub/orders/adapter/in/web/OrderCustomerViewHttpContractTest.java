package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.ViewCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;

class OrderCustomerViewHttpContractTest {

    @Test
    void readsOwnOrderUsingTrustedActorAndPathOrderIdOnly()
            throws Exception {

        var createCustomerOrderUseCase =
                mock(
                        CreateCustomerOrderUseCase.class);

        var viewCustomerOrderUseCase =
                mock(
                        ViewCustomerOrderUseCase.class);

        var constructors =
                Arrays.stream(
                                OrderController.class
                                        .getDeclaredConstructors())
                        .filter(constructor -> {

                            var types =
                                    Arrays.asList(
                                            constructor.getParameterTypes());

                            return types.size() == 3
                                    && types.contains(
                                            CreateCustomerOrderUseCase.class)
                                    && types.contains(
                                            ViewCustomerOrderUseCase.class)
                                    && types.contains(
                                            int.class);
                        })
                        .toList();

        assertThat(constructors)
                .as(
                        "Own-Order HTTP controller constructor")
                .hasSize(1);

        var constructor =
                constructors.getFirst();

        constructor.setAccessible(
                true);

        var constructorTypes =
                constructor.getParameterTypes();

        var constructorArguments =
                new Object[constructorTypes.length];

        for (
                int index = 0;
                index < constructorTypes.length;
                index++
        ) {

            var type =
                    constructorTypes[index];

            if (
                type
                        == CreateCustomerOrderUseCase.class
            ) {

                constructorArguments[index] =
                        createCustomerOrderUseCase;

            } else if (
                type
                        == ViewCustomerOrderUseCase.class
            ) {

                constructorArguments[index] =
                        viewCustomerOrderUseCase;

            } else if (
                type == int.class
            ) {

                constructorArguments[index] =
                        100;

            } else {

                throw new AssertionError(
                        "Unexpected OrderController constructor dependency: "
                                + type.getName());
            }
        }

        var controller =
                (OrderController)
                        constructor.newInstance(
                                constructorArguments);

        var actorUserId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var actorContext =
                new TrustedActorContext(
                        actorUserId,
                        tenantId);

        var order =
                Order.create(
                        orderId,
                        tenantId,
                        customerId,
                        List.of(
                                new OrderItem(
                                        variantId,
                                        2)));

        when(
                viewCustomerOrderUseCase.view(
                        actorUserId,
                        tenantId,
                        orderId))
                .thenReturn(
                        order);

        var trustedActorResolver =
                new HandlerMethodArgumentResolver() {

                    @Override
                    public boolean supportsParameter(
                            MethodParameter parameter) {

                        return parameter
                                .getParameterType()
                                .equals(
                                        TrustedActorContext.class);
                    }

                    @Override
                    public Object resolveArgument(
                            MethodParameter parameter,
                            ModelAndViewContainer mavContainer,
                            NativeWebRequest webRequest,
                            WebDataBinderFactory binderFactory) {

                        return actorContext;
                    }
                };

        var mockMvc =
                MockMvcBuilders
                        .standaloneSetup(
                                controller)
                        .setCustomArgumentResolvers(
                                trustedActorResolver)
                        .build();

        mockMvc.perform(
                        get(
                                "/orders/{orderId}",
                                orderId)
                                .accept(
                                        MediaType.APPLICATION_JSON))
                .andExpect(
                        status().isOk())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON))
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        orderId.toString()))
                .andExpect(
                        jsonPath("$.tenantId")
                                .value(
                                        tenantId.toString()))
                .andExpect(
                        jsonPath("$.customerId")
                                .value(
                                        customerId.toString()))
                .andExpect(
                        jsonPath("$.status")
                                .value(
                                        "CREATED"))
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(
                                        1))
                .andExpect(
                        jsonPath("$.items[0].variantId")
                                .value(
                                        variantId.toString()))
                .andExpect(
                        jsonPath("$.items[0].quantity")
                                .value(
                                        2))
                .andExpect(
                        jsonPath("$.allocationOutcome")
                                .doesNotExist());

        verify(
                viewCustomerOrderUseCase)
                .view(
                        actorUserId,
                        tenantId,
                        orderId);

        verifyNoInteractions(
                createCustomerOrderUseCase);
    }
}

package io.github.piresrenan.orderhub.orders.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.ViewCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.service.ViewCustomerOrderService;

class OrdersCustomerOrderViewConfigurationTest {

    @Test
    void exposesViewCustomerOrderUseCaseAsExplicitSpringBean()
            throws Exception {

        var candidates =
                Arrays.stream(
                                OrdersConfiguration.class
                                        .getDeclaredMethods())
                        .filter(method ->
                                method.getName()
                                        .equals(
                                                "viewCustomerOrderUseCase"))
                        .toList();

        assertThat(candidates)
                .as(
                        "ViewCustomerOrderUseCase composition bean")
                .hasSize(1);

        var method =
                candidates.getFirst();

        assertThat(method.getReturnType())
                .isEqualTo(
                        ViewCustomerOrderUseCase.class);

        assertThat(method.getParameterTypes())
                .containsExactly(
                        OrderRepository.class,
                        ResolveCustomerAccountBindingUseCase.class,
                        AuthorizeCustomerOwnedResourceActionUseCase.class);

        assertThat(
                method.isAnnotationPresent(
                        Bean.class))
                .isTrue();

        method.setAccessible(
                true);

        var orders =
                mock(
                        OrderRepository.class);

        var bindings =
                mock(
                        ResolveCustomerAccountBindingUseCase.class);

        var authorization =
                mock(
                        AuthorizeCustomerOwnedResourceActionUseCase.class);

        var bean =
                method.invoke(
                        new OrdersConfiguration(),
                        orders,
                        bindings,
                        authorization);

        assertThat(bean)
                .isInstanceOf(
                        ViewCustomerOrderService.class);
    }
}

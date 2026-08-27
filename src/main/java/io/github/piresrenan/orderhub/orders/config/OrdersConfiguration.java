package io.github.piresrenan.orderhub.orders.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

import io.github.piresrenan.orderhub.orders.adapter.out.persistence.memory.InMemoryOrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.service.CreateOrderService;

@Configuration(proxyBeanMethods = false)
public class OrdersConfiguration {

    /**
     * Provides the current OrderRepository implementation used by the application.
     *
     * <p>
     * The in-memory adapter is intentionally temporary and will be replaced by
     * durable PostgreSQL persistence without changing the application port.
     * </p>
     *
     * @return repository adapter used for order persistence
     */
    @Bean
    OrderRepository orderRepository() {
        return new InMemoryOrderRepository();
    }

    /**
     * Provides the production identity-generation strategy for new orders.
     *
     * @return generator backed by UUID.randomUUID
     */
    @Bean
    OrderIdGenerator orderIdGenerator() {
        return UUID::randomUUID;
    }

    /**
     * Composes the order creation use case with its required output ports.
     *
     * <p>
     * Keeping framework composition in configuration allows the application
     * service itself to remain independent of Spring annotations.
     * </p>
     *
     * @param orderRepository  configured order persistence port
     * @param orderIdGenerator configured order identity-generation port
     * @return application input port ready to process order creation commands
     */
    @Bean
    CreateOrderUseCase createOrderUseCase(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator) {
        return new CreateOrderService(
                orderRepository,
                orderIdGenerator);
    }
}

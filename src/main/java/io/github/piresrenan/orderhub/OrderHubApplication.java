package io.github.piresrenan.orderhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderHubApplication {

    /**
     * Starts the OrderHub Spring Boot application and initializes the application
     * context, HTTP server and configured infrastructure adapters.
     *
     * @param args optional command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderHubApplication.class, args);
    }
}

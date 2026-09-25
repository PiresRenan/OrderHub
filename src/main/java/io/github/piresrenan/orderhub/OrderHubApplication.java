package io.github.piresrenan.orderhub;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.piresrenan.orderhub.bootstrap.adapter.in.command.FirstOperatorBootstrapCommand;

@SpringBootApplication
public class OrderHubApplication {

    /**
     * Starts the OrderHub Spring Boot application and initializes the application
     * context, HTTP server and configured infrastructure adapters, or runs the
     * offline first-operator bootstrap command when explicitly selected.
     *
     * @param args optional command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        // ADR-0022: the offline first-operator command is selected only by an explicit first argument.
        if (args.length > 0 && FirstOperatorBootstrapCommand.NAME.equals(args[0])) {
            System.exit(FirstOperatorBootstrapCommand.run(OrderHubApplication.class,
                    Arrays.copyOfRange(args, 1, args.length), System.out).exitCode());
            return;
        }
        SpringApplication.run(OrderHubApplication.class, args);
    }
}

package io.github.piresrenan.orderhub.bootstrap.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorBootstrapTransaction;

/**
 * REQUIRED transaction on the application's single PlatformTransactionManager.
 * Users and Authorization scopes use the same manager with REQUIRED and so join
 * this transaction instead of committing independently.
 */
public final class SpringFirstOperatorBootstrapTransaction implements FirstOperatorBootstrapTransaction {

    /** Bounds lock waiting and the whole one-shot transaction; not a latency SLA. */
    static final int TIMEOUT_SECONDS = 15;

    private final TransactionTemplate template;

    /** Configures REQUIRED propagation with a bounded timeout on the shared manager. */
    public SpringFirstOperatorBootstrapTransaction(PlatformTransactionManager manager) {
        template = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setTimeout(TIMEOUT_SECONDS);
    }

    /** Runs the ceremony in one transaction that rolls back on any runtime failure. */
    @Override
    public <T> T execute(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return template.execute(status -> work.get());
    }
}

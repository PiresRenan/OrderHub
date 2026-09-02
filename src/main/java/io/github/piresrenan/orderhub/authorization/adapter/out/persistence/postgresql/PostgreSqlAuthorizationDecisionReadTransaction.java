package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationDecisionReadTransaction;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;

/**
 * PostgreSQL-backed coherent read boundary for one authorization decision.
 *
 * <p>
 * REPEATABLE READ ensures every durable policy statement participating in one
 * decision observes one consistent PostgreSQL snapshot instead of independently
 * observing different committed privilege states.
 *
 * <p>
 * The boundary uses an independent physical transaction so an enclosing
 * READ_COMMITTED business transaction cannot silently downgrade the authorization
 * snapshot isolation.
 * </p>
 * </p>
 */
public final class PostgreSqlAuthorizationDecisionReadTransaction
        implements AuthorizationDecisionReadTransaction {

    private final TransactionTemplate transactionTemplate;

    public PostgreSqlAuthorizationDecisionReadTransaction(
            PlatformTransactionManager transactionManager) {

        Objects.requireNonNull(
                transactionManager,
                "transactionManager");

        this.transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);

        transactionTemplate.setReadOnly(
                true);
    }

    @Override
    public AuthorizationDecision execute(
            Supplier<AuthorizationDecision> decision) {

        Objects.requireNonNull(
                decision,
                "decision");

        try {
            var result =
                    transactionTemplate.execute(
                            status ->
                                    decision.get());

            if (result == null) {
                throw new AuthorizationPersistenceException(
                        "Authorization read transaction produced no decision");
            }

            return result;

        } catch (AuthorizationPersistenceException exception) {

            throw exception;

        } catch (TransactionException exception) {

            throw new AuthorizationPersistenceException(
                    exception);
        }
    }
}

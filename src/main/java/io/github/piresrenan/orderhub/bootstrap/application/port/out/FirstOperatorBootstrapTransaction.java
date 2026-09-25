package io.github.piresrenan.orderhub.bootstrap.application.port.out;

import java.util.function.Supplier;

/** One authoritative REQUIRED transaction shared by the ceremony and every owner API it composes. */
public interface FirstOperatorBootstrapTransaction {

    /**
     * Runs the work in one transaction that commits or rolls back as a whole.
     *
     * @param work ceremony work
     * @param <T>  result type
     * @return work result after commit
     */
    <T> T execute(Supplier<T> work);
}

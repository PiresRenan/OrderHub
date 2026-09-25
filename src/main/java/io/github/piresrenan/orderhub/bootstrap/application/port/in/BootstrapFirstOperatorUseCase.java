package io.github.piresrenan.orderhub.bootstrap.application.port.in;

/** Runs the retained first-operator ceremony once for exact, already-verified coordinates. */
public interface BootstrapFirstOperatorUseCase {

    /**
     * Attempts the one-shot transition inside one authoritative transaction.
     *
     * @param request exact issuer, subject and ceremony operation identifier
     * @return bounded outcome; persistence failures propagate after full rollback
     */
    FirstOperatorBootstrapOutcome bootstrap(FirstOperatorBootstrapRequest request);
}

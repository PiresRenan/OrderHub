package io.github.piresrenan.orderhub.authorization.application.port.out;

/** Owner-local reads that the first-operator ceremony needs inside the caller transaction. */
public interface FirstOperatorAuthorityRepository {

    /**
     * Reports whether any Platform-scope grant exists, requiring an ambient transaction.
     *
     * @return true when Platform authority is already held by some User
     */
    boolean platformAuthorityExists();
}

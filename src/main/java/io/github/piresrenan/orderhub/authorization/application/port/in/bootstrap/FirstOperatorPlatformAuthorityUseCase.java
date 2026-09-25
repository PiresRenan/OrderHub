package io.github.piresrenan.orderhub.authorization.application.port.in.bootstrap;

import java.util.UUID;

/**
 * Establishes the minimum Platform authority of the retained first operator.
 *
 * <p>Both operations require the caller's ambient transaction and never commit
 * independently. The grant vocabulary is fixed to
 * {@code PLATFORM_TENANTS_MANAGE}; this is not a generic root-grant port.</p>
 */
public interface FirstOperatorPlatformAuthorityUseCase {

    /**
     * Reports whether any Platform-scope administrative grant already exists.
     *
     * @return true when some User already holds Platform authority
     */
    boolean platformAuthorityExists();

    /**
     * Grants exactly {@code PLATFORM_TENANTS_MANAGE} at Platform scope to one
     * User that must not already hold it.
     *
     * @param userId internal User established by the ceremony
     * @throws IllegalStateException when the grant was not newly applied
     */
    void establishFirstOperatorAuthority(UUID userId);
}

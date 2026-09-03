package io.github.piresrenan.orderhub.workforce.application.port.in;

import io.github.piresrenan.orderhub.workforce.domain.model.EffectiveWorkforceAuthority;
import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffPlacement;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;

/**
 * Framework-neutral boundary for resolving one Staff relationship's current
 * workforce authority ceiling.
 *
 * <p>
 * Persistence is deliberately absent from this contract. A later adapter may
 * resolve durable workforce state before invoking the same domain semantics.
 * </p>
 */
@FunctionalInterface
public interface ResolveEffectiveWorkforceAuthorityUseCase {

    EffectiveWorkforceAuthority resolve(
            StaffProfile staff,
            StaffPlacement placement,
            JobPosition position);
}

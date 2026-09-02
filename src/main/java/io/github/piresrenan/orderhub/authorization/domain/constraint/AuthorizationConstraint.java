package io.github.piresrenan.orderhub.authorization.domain.constraint;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;

/**
 * One independent restriction applied to an otherwise eligible authorization
 * decision.
 *
 * <p>
 * Constraints may only preserve eligibility or reduce it. They never create a
 * permission grant.
 * </p>
 */
@FunctionalInterface
public interface AuthorizationConstraint {

    AuthorizationDecision evaluate(
            AuthorizationConstraintContext context);
}

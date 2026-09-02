package io.github.piresrenan.orderhub.authorization.domain.constraint;

import java.util.Set;
import java.util.regex.Pattern;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;

/**
 * Static separation-of-duty rule expressed as mutually exclusive role
 * assignments for one User/persona/scope.
 *
 * <p>
 * The constraint models policy mechanics only. OH-013 deliberately does not
 * invent concrete business conflicts before the corresponding workflows exist.
 * </p>
 */
public record StaticSeparationOfDutyConstraint(
        String code,
        Set<String> mutuallyExclusiveRoleCodes)
        implements AuthorizationConstraint {

    private static final Pattern CODE_PATTERN =
            Pattern.compile(
                    "[A-Z][A-Z0-9_]{2,63}");

    public StaticSeparationOfDutyConstraint {

        if (code == null
                || !CODE_PATTERN
                        .matcher(code)
                        .matches()) {

            throw new IllegalArgumentException(
                    "Constraint code must use stable upper snake case");
        }

        if (mutuallyExclusiveRoleCodes == null) {
            throw new IllegalArgumentException(
                    "Mutually exclusive role codes are required");
        }

        if (mutuallyExclusiveRoleCodes.stream()
                .anyMatch(roleCode ->
                        roleCode == null
                                || !CODE_PATTERN
                                        .matcher(roleCode)
                                        .matches())) {

            throw new IllegalArgumentException(
                    "Mutually exclusive roles must use stable upper snake case");
        }

        mutuallyExclusiveRoleCodes =
                Set.copyOf(
                        mutuallyExclusiveRoleCodes);

        if (mutuallyExclusiveRoleCodes.size() < 2) {
            throw new IllegalArgumentException(
                    "Static separation of duty requires at least two roles");
        }
    }

    @Override
    public AuthorizationDecision evaluate(
            AuthorizationConstraintContext context) {

        if (context == null) {
            throw new IllegalArgumentException(
                    "Authorization constraint context is required");
        }

        var request =
                context.request();

        var matchingRoleCount =
                context.assignments()
                        .stream()
                        .filter(assignment ->
                                assignment.appliesTo(
                                        request.userId(),
                                        request.persona(),
                                        request.scope()))
                        .map(assignment ->
                                assignment.roleCode())
                        .filter(
                                mutuallyExclusiveRoleCodes::contains)
                        .distinct()
                        .limit(2)
                        .count();

        if (matchingRoleCount >= 2) {
            return AuthorizationDecision.DENY;
        }

        return AuthorizationDecision.ALLOW;
    }
}

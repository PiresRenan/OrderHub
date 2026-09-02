package io.github.piresrenan.orderhub.authorization.domain.model;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reusable permission bundle constrained by a definition envelope.
 *
 * <p>
 * Roles improve administration ergonomics. Authorization decisions are still
 * made against atomic permissions.
 * </p>
 */
public record RoleDefinition(
        String code,
        AuthorizationPersona persona,
        AuthorityBand authorityBand,
        RoleMutability mutability,
        Set<PermissionCode> permissions,
        PermissionEnvelope permissionEnvelope) {

    private static final Pattern CODE_PATTERN =
            Pattern.compile(
                    "[A-Z][A-Z0-9_]{2,63}");

    public RoleDefinition {

        if (code == null
                || !CODE_PATTERN
                        .matcher(code)
                        .matches()) {

            throw new IllegalArgumentException(
                    "Role code must use stable upper snake case");
        }

        if (persona == null) {
            throw new IllegalArgumentException(
                    "Role persona is required");
        }

        if (persona
                != AuthorizationPersona.STAFF) {

            throw new IllegalArgumentException(
                    "Employee role definitions require the STAFF persona");
        }

        if (authorityBand == null) {
            throw new IllegalArgumentException(
                    "Role authority band is required");
        }

        if (mutability == null) {
            throw new IllegalArgumentException(
                    "Role mutability is required");
        }

        if (permissions == null) {
            throw new IllegalArgumentException(
                    "Role permissions are required");
        }

        if (permissions.stream()
                .anyMatch(permission ->
                        permission == null)) {

            throw new IllegalArgumentException(
                    "Role permissions cannot contain null");
        }

        permissions =
                Set.copyOf(
                        permissions);

        if (permissionEnvelope == null) {
            throw new IllegalArgumentException(
                    "Role permission envelope is required");
        }

        if (!permissionEnvelope
                .containsAll(
                        permissions)) {

            throw new IllegalArgumentException(
                    "Role permissions exceed its permission envelope");
        }

        if (permissions.stream()
                .anyMatch(permission ->
                        !permission.supports(
                                persona))) {

            throw new IllegalArgumentException(
                    "Role contains a permission incompatible with its persona");
        }
    }
}

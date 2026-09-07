package io.github.piresrenan.orderhub.authorization.domain.model;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

@NamedInterface({
    "policy-model",
    "administration"
})
public record AdministrativeScope(
        AdministrativeScopeType type,
        UUID scopeId) {

    public AdministrativeScope {

        if (type == null) {
            throw new IllegalArgumentException(
                    "Administrative scope type is required");
        }

        if (type == AdministrativeScopeType.PLATFORM
                && scopeId != null) {

            throw new IllegalArgumentException(
                    "Platform administrative scope cannot carry a resource id");
        }

        if (type != AdministrativeScopeType.PLATFORM
                && scopeId == null) {

            throw new IllegalArgumentException(
                    "Scoped administrative scope requires a resource id");
        }
    }

    public static AdministrativeScope platform() {

        return new AdministrativeScope(
                AdministrativeScopeType.PLATFORM,
                null);
    }

    public static AdministrativeScope organization(
            UUID organizationId) {

        return new AdministrativeScope(
                AdministrativeScopeType.ORGANIZATION,
                organizationId);
    }

    public static AdministrativeScope tenant(
            UUID tenantId) {

        return new AdministrativeScope(
                AdministrativeScopeType.TENANT,
                tenantId);
    }
}

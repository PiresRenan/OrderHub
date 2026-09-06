package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

@NamedInterface({
    "policy-model",
    "administration"
})
public enum AdministrativeScopeType {

    PLATFORM,
    ORGANIZATION,
    TENANT
}

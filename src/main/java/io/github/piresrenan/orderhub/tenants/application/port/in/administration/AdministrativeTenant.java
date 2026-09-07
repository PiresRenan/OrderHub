package io.github.piresrenan.orderhub.tenants.application.port.in.administration;

import java.util.UUID;

public record AdministrativeTenant(UUID id, String name, String status) {
}

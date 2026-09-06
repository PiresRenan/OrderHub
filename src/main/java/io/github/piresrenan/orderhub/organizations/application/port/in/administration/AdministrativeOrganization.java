package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

import java.util.UUID;

public record AdministrativeOrganization(UUID id, String name, String status) {
}

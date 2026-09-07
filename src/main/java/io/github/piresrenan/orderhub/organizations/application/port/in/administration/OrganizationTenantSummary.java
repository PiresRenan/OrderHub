package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

import java.util.UUID;

public record OrganizationTenantSummary(UUID id, String name, String status) {
}

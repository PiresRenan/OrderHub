package io.github.piresrenan.orderhub.tenants.application.port.in.operational;

import java.util.Optional;

public interface FindTenantOperationalStateUseCase {

    Optional<TenantOperationalState> find(
            FindTenantOperationalStateQuery query);
}

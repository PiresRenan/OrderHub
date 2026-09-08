package io.github.piresrenan.orderhub.inventory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.StaffAuthorizationUnavailableException;
import io.github.piresrenan.orderhub.catalog.application.port.in.identity.*;
import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.*;
import io.github.piresrenan.orderhub.inventory.adapter.out.transaction.spring.SpringInventoryAdministrationTransactionExecutor;
import io.github.piresrenan.orderhub.inventory.application.port.in.*;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException.Reason;
import io.github.piresrenan.orderhub.inventory.application.port.out.*;
import io.github.piresrenan.orderhub.inventory.application.service.*;

/** Inventory owns writes and evidence; cross-owner identity uses only Catalog's named contract. */
@Configuration(proxyBeanMethods=false)
public class InventoryAdministrationConfiguration {
    @Bean InventoryAdministrationAuthorization inventoryAdministrationAuthorization(AuthorizeStaffTenantActionUseCase authority) {
        return (actor,tenant,action)-> {
            var code=switch(action) {
                case VIEW -> PermissionCode.INVENTORY_VIEW;
                case RECEIVE -> PermissionCode.INVENTORY_RECEIVE;
                case ADJUST -> PermissionCode.INVENTORY_ADJUST;
                case POLICY_MANAGE -> PermissionCode.INVENTORY_POLICY_MANAGE;
            };
            try {
                if(authority.authorize(actor,tenant,code)!=AuthorizationDecision.ALLOW)
                    throw new InventoryAdministrationException(Reason.ACCESS_DENIED);
            } catch(StaffAuthorizationUnavailableException exception) { throw new InventoryAdministrationException(Reason.TECHNICAL); }
        };
    }
    @Bean InventoryAdministrationTransactionExecutor inventoryAdministrationTransactionExecutor(PlatformTransactionManager manager,
            @Value("${orderhub.inventory.administration.transaction-timeout-seconds:5}") int timeout) {
        if(timeout<1) throw new IllegalArgumentException("Inventory administration timeout must be positive");
        var tx=new TransactionTemplate(manager); tx.setTimeout(timeout);
        return new SpringInventoryAdministrationTransactionExecutor(tx);
    }
    @Bean InventoryVariantIdentityValidator inventoryVariantIdentityValidator(ValidateVariantIdentityUseCase catalog) {
        return (tenant,variant)-> {
            try { catalog.validate(tenant,variant); }
            catch(CatalogVariantIdentityRejectedException exception) { throw new InventoryAdministrationException(Reason.TARGET_UNAVAILABLE); }
            catch(CatalogVariantIdentityUnavailableException exception) { throw new InventoryAdministrationException(Reason.TECHNICAL); }
        };
    }
    @Bean InventoryMovementRepository inventoryMovementRepository(JdbcTemplate jdbc) { return new PostgreSqlInventoryMovementRepository(jdbc); }
    @Bean InventoryAdministrationReadRepository inventoryAdministrationReadRepository(JdbcTemplate jdbc) { return new PostgreSqlInventoryAdministrationReadRepository(jdbc); }
    @Bean InventoryAdministrationReadService inventoryAdministrationReadService(InventoryAdministrationAuthorization a,InventoryAdministrationTransactionExecutor tx,
            InventoryAdministrationReadRepository reads) { return new InventoryAdministrationReadService(a,tx,reads); }
    @Bean InventoryPolicyAdministrationRepository inventoryPolicyAdministrationRepository(JdbcTemplate jdbc) {
        return new PostgreSqlInventoryPolicyAdministrationRepository(jdbc);
    }
    @Bean RecordInventoryMovementUseCase recordInventoryMovementUseCase(InventoryAdministrationAuthorization a,InventoryAdministrationTransactionExecutor tx,
            InventoryVariantIdentityValidator identities,InventoryMovementRepository movements,InventoryTimeProvider time) {
        return new RecordInventoryMovementService(a,tx,identities,movements,time);
    }
    @Bean InventoryPolicyAdministrationService inventoryPolicyAdministrationService(InventoryAdministrationAuthorization a,InventoryAdministrationTransactionExecutor tx,
            InventoryPolicyAdministrationRepository repository,InventoryTimeProvider time) {
        return new InventoryPolicyAdministrationService(a,tx,repository,time);
    }
}

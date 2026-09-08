package io.github.piresrenan.orderhub.catalog.config;

import java.time.Clock;
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
import io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql.*;
import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCatalogAdminTransactionExecutor;
import io.github.piresrenan.orderhub.catalog.application.port.in.SaveCategoryUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.identity.ValidateVariantIdentityUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.application.service.*;

/** Composes owner-local administration with the established current Staff authority boundary. */
@Configuration(proxyBeanMethods=false)
public class CatalogAdministrationConfiguration {
    @Bean CatalogAdminAuthorizer catalogAdminAuthorizer(AuthorizeStaffTenantActionUseCase authority) {
        return (actor,permission)-> {
            var code=switch(permission) {
                case VIEW -> PermissionCode.CATALOG_VIEW;
                case MANAGE -> PermissionCode.CATALOG_MANAGE;
                case PRICE_MANAGE -> PermissionCode.CATALOG_PRICE_MANAGE;
            };
            try {
                if(authority.authorize(actor.userId(),actor.tenantId(),code)!=AuthorizationDecision.ALLOW)
                    throw new CatalogAdminDeniedException();
            } catch(StaffAuthorizationUnavailableException exception) { throw new CatalogAdminUnavailableException(); }
        };
    }
    @Bean CatalogAdministrationRepository catalogAdministrationRepository(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        return new PostgreSqlCatalogAdministrationRepository(jdbc,new TransactionTemplate(manager));
    }
    @Bean CatalogAdminTransactionExecutor catalogAdminTransactionExecutor(PlatformTransactionManager manager,
            @Value("${orderhub.catalog.administration.transaction-timeout-seconds:5}") int timeout) {
        if(timeout<1) throw new IllegalArgumentException("Catalog administration timeout must be positive");
        var tx=new TransactionTemplate(manager); tx.setTimeout(timeout);
        return new PostgreSqlCatalogAdminTransactionExecutor(tx);
    }
    @Bean CatalogAdministrationService catalogAdministrationService(CatalogAdminAuthorizer a,CatalogAdministrationRepository r,CatalogAdminTransactionExecutor tx) {
        return new CatalogAdministrationService(a,r,tx,Clock.systemUTC());
    }
    @Bean CatalogCategoryAdministrationService catalogCategoryAdministrationService(CatalogAdminAuthorizer a,CatalogAdministrationRepository r,
            CatalogAdminTransactionExecutor tx,CategoryHierarchyMutationExecutor guard,SaveCategoryUseCase save) {
        return new CatalogCategoryAdministrationService(a,r,tx,guard,save,Clock.systemUTC());
    }
    @Bean CatalogPricingAdministrationService catalogPricingAdministrationService(CatalogAdminAuthorizer a,CatalogAdministrationRepository r,CatalogAdminTransactionExecutor tx) {
        return new CatalogPricingAdministrationService(a,r,tx,Clock.systemUTC());
    }
    @Bean CatalogAdministrationReadService catalogAdministrationReadService(CatalogAdminAuthorizer a,CatalogAdministrationRepository r,CatalogAdminTransactionExecutor tx) {
        return new CatalogAdministrationReadService(a,r,tx);
    }
    @Bean ValidateVariantIdentityUseCase validateVariantIdentityUseCase(JdbcTemplate jdbc) { return new PostgreSqlValidateVariantIdentity(jdbc); }
}

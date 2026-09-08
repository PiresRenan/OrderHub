package io.github.piresrenan.orderhub.catalog.application.service;
import java.time.Clock;
import java.util.UUID;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.domain.model.VariantBasePrice;
import io.github.piresrenan.orderhub.catalog.domain.model.Money;
/** Explicit exact-money administration, independent of Catalog metadata permission. */
public final class CatalogPricingAdministrationService {
    private final CatalogAdminAuthorizer authorization;
    private final CatalogAdministrationRepository repository;
    private final CatalogAdminTransactionExecutor transactions;
    private final Clock clock;
    /** Requires owner authorization, persistence and an atomic evidence transaction. */
    public CatalogPricingAdministrationService(CatalogAdminAuthorizer a,CatalogAdministrationRepository r,
            CatalogAdminTransactionExecutor tx,Clock clock) {
        authorization=java.util.Objects.requireNonNull(a); repository=java.util.Objects.requireNonNull(r);
        transactions=java.util.Objects.requireNonNull(tx); this.clock=java.util.Objects.requireNonNull(clock);
    }
    /** Sets one currency's base price against its current resource revision. */
    public CatalogRevision<VariantBasePrice> set(CatalogAdminContext actor,UUID variant,String currency,long expected,long minorUnits) {
        authorization.require(actor,CatalogAdminPermission.PRICE_MANAGE);
        var price=VariantBasePrice.create(actor.tenantId(),variant,Money.of(currency,minorUnits));
        if(expected<0 || expected==Long.MAX_VALUE) throw new CatalogAdminConflictException();
        return transactions.execute(()-> {
            repository.variant(actor.tenantId(),variant,false).orElseThrow(CatalogAdminNotFoundException::new);
            var before=repository.price(actor.tenantId(),variant,currency,true);
            if(before.map(CatalogRevision::revision).orElse(0L)!=expected) throw new CatalogAdminConflictException();
            repository.savePrice(price,expected);
            repository.appendAudit(new CatalogAuditEvidence(UUID.randomUUID(),actor.tenantId(),actor.userId(),variant,
                    "BASE_PRICE_SET",expected,expected+1,actor.correlationId(),clock.instant(),currency,
                    before.map(value->value.value().minorUnits()).orElse(null),minorUnits));
            return new CatalogRevision<>(price,expected+1);
        });
    }
    /** Reads one explicit currency price under Catalog read permission. */
    public CatalogRevision<VariantBasePrice> get(CatalogAdminContext actor,UUID variant,String currency) {
        authorization.require(actor,CatalogAdminPermission.VIEW); Money.of(currency,0);
        return transactions.execute(()->repository.price(actor.tenantId(),variant,currency,false)
                .orElseThrow(CatalogAdminNotFoundException::new));
    }
}

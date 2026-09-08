package io.github.piresrenan.orderhub.catalog.application.service;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;
/** Tenant-owned administration discovery with explicit permission and bounded keyset pages. */
public final class CatalogAdministrationReadService {
    private final CatalogAdminAuthorizer authorization;
    private final CatalogAdministrationRepository repository;
    private final CatalogAdminTransactionExecutor transactions;
    public CatalogAdministrationReadService(CatalogAdminAuthorizer authorization,CatalogAdministrationRepository repository,
            CatalogAdminTransactionExecutor transactions) {
        this.authorization=Objects.requireNonNull(authorization);
        this.repository=Objects.requireNonNull(repository);
        this.transactions=Objects.requireNonNull(transactions);
    }
    /** Returns at most limit Products strictly after the PostgreSQL UUID cursor. */
    public List<CatalogProductSummary> products(CatalogAdminContext actor,UUID afterId,int limit) {
        authorization.require(actor,CatalogAdminPermission.VIEW); bounded(limit);
        return transactions.execute(()->repository.products(actor.tenantId(),afterId,limit));
    }
    /** Lists only the selected Tenant-owned Product's Variants. */
    public List<CatalogVariantSummary> variants(CatalogAdminContext actor,UUID productId,UUID afterId,int limit) {
        authorization.require(actor,CatalogAdminPermission.VIEW); bounded(limit);
        return transactions.execute(()-> {
            repository.product(actor.tenantId(),productId,false).orElseThrow(CatalogAdminNotFoundException::new);
            return repository.variants(actor.tenantId(),productId,afterId,limit);
        });
    }
    /** Lists flat Category nodes; never recursively expands an unbounded tree. */
    public List<CatalogRevision<Category>> categories(CatalogAdminContext actor,UUID afterId,int limit) {
        authorization.require(actor,CatalogAdminPermission.VIEW); bounded(limit);
        return transactions.execute(()->repository.categories(actor.tenantId(),afterId,limit));
    }
    private static void bounded(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid page size");
    }
}

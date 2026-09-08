package io.github.piresrenan.orderhub.catalog.application.service;
import java.time.Clock;
import java.util.UUID;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.SaveCategoryUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;
/** Administrative Category commands retain the established Tenant hierarchy guard. */
public final class CatalogCategoryAdministrationService {
    private final CatalogAdminAuthorizer authorization;
    private final CatalogAdministrationRepository repository;
    private final CatalogAdminTransactionExecutor transactions;
    private final CategoryHierarchyMutationExecutor hierarchy;
    private final SaveCategoryUseCase save;
    private final Clock clock;
    /** Requires the established hierarchy workflow rather than duplicating its validator. */
    public CatalogCategoryAdministrationService(CatalogAdminAuthorizer a,CatalogAdministrationRepository r,
            CatalogAdminTransactionExecutor tx,CategoryHierarchyMutationExecutor guard,SaveCategoryUseCase save,Clock clock) {
        authorization=java.util.Objects.requireNonNull(a); repository=java.util.Objects.requireNonNull(r);
        transactions=java.util.Objects.requireNonNull(tx); hierarchy=java.util.Objects.requireNonNull(guard);
        this.save=java.util.Objects.requireNonNull(save); this.clock=java.util.Objects.requireNonNull(clock);
    }
    /** Creates one bounded Category under its Tenant's hierarchy guard. */
    public CatalogRevision<Category> create(CatalogAdminContext actor,UUID id,UUID parent,String name,String slug,String description) {
        authorization.require(actor,CatalogAdminPermission.MANAGE);
        bounded(name,description);
        var category=Category.create(id,actor.tenantId(),parent,name,slug,description);
        return transactions.execute(()-> {
            var result=hierarchy.execute(actor.tenantId(),()-> {
                if(repository.category(actor.tenantId(),id).isPresent()) throw new CatalogAdminConflictException();
                var created=save.save(category); audit(actor,id,"CATEGORY_CREATED",0); return created;
            });
            return new CatalogRevision<>(result,1);
        });
    }
    /** Moves a Category only from the expected current revision. */
    public CatalogRevision<Category> reparent(CatalogAdminContext actor,UUID id,long expected,UUID parent) {
        authorization.require(actor,CatalogAdminPermission.MANAGE);
        return change(actor,id,expected,"CATEGORY_REPARENTED",old->Category.create(old.id(),old.tenantId(),parent,
                old.name(),old.slug(),old.description()));
    }
    /** Edits bounded descriptive fields without changing the current hierarchy. */
    public CatalogRevision<Category> metadata(CatalogAdminContext actor,UUID id,long expected,String name,String slug,String description) {
        authorization.require(actor,CatalogAdminPermission.MANAGE); bounded(name,description);
        return change(actor,id,expected,"CATEGORY_METADATA_CHANGED",old->Category.create(old.id(),old.tenantId(),old.parentCategoryId(),name,slug,description));
    }
    /** Returns a Tenant-owned Category only after explicit Catalog read permission. */
    public CatalogRevision<Category> get(CatalogAdminContext actor,UUID id) {
        authorization.require(actor,CatalogAdminPermission.VIEW);
        return transactions.execute(()->repository.category(actor.tenantId(),id).orElseThrow(CatalogAdminNotFoundException::new));
    }
    /** Acquires the hierarchy guard before reading, checking revisions, validating ancestry and auditing. */
    private CatalogRevision<Category> change(CatalogAdminContext actor,UUID id,long expected,String action,java.util.function.Function<Category,Category> mutation) {
        return transactions.execute(()-> {
            var result=hierarchy.execute(actor.tenantId(),()-> {
                var current=repository.category(actor.tenantId(),id).orElseThrow(CatalogAdminNotFoundException::new);
                if(expected<1 || expected==Long.MAX_VALUE || current.revision()!=expected) throw new CatalogAdminConflictException();
                var changed=mutation.apply(current.value());
                repository.advanceCategory(actor.tenantId(),id,expected);
                var saved=save.save(changed); audit(actor,id,action,expected); return saved;
            });
            return new CatalogRevision<>(result,expected+1);
        });
    }
    /** Records one successful Category revision transition without persisting arbitrary descriptions. */
    private void audit(CatalogAdminContext actor,UUID id,String action,long before) {
        repository.appendAudit(new CatalogAuditEvidence(UUID.randomUUID(),actor.tenantId(),actor.userId(),id,action,
                before,before+1,actor.correlationId(),clock.instant()));
    }
    /** Bounds administrative descriptions; existing Category validation retains semantic ownership. */
    private static void bounded(String name,String description) {
        if(name==null || name.codePointCount(0,name.length())>160 || (description!=null && description.codePointCount(0,description.length())>4000))
            throw new IllegalArgumentException("Invalid Category metadata");
    }
}

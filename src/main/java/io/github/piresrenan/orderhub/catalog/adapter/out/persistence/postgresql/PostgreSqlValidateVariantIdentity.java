package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.catalog.application.port.in.identity.*;
/** Stabilizes only identity, allowing authoring-state receipt without implying sellability. */
public final class PostgreSqlValidateVariantIdentity implements ValidateVariantIdentityUseCase {
    private final JdbcTemplate jdbc;
    /** Uses the consuming workflow's existing connection and transaction. */
    public PostgreSqlValidateVariantIdentity(JdbcTemplate jdbc) { this.jdbc=java.util.Objects.requireNonNull(jdbc); }
    /** Acquires the narrow key lock before Inventory position mutation. */
    @Override public void validate(UUID tenantId,UUID variantId) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new CatalogVariantIdentityUnavailableException();
        try {
            if(jdbc.query("SELECT id FROM catalog.product_variants WHERE tenant_id=? AND id=? FOR KEY SHARE",
                    (row,n)->row.getObject(1,UUID.class),tenantId,variantId).isEmpty())
                throw new CatalogVariantIdentityRejectedException();
        } catch(DataAccessException exception) { throw new CatalogVariantIdentityUnavailableException(); }
    }
}

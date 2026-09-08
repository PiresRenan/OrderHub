package io.github.piresrenan.orderhub.catalog.application.port.out;
import java.time.Instant;
import java.util.UUID;
/** Minimal append-only operational evidence, with no copied commercial payload. */
public record CatalogAuditEvidence(UUID id, UUID tenantId, UUID actorId, UUID resourceId,
        String action, long beforeRevision, long afterRevision, String correlationId, Instant occurredAt,
        String currencyCode,Long beforeMinorUnits,Long afterMinorUnits) {
    /** Creates nonfinancial evidence without copying arbitrary commercial metadata. */
    public CatalogAuditEvidence(UUID id,UUID tenantId,UUID actorId,UUID resourceId,String action,long beforeRevision,
            long afterRevision,String correlationId,Instant occurredAt) {
        this(id,tenantId,actorId,resourceId,action,beforeRevision,afterRevision,correlationId,occurredAt,null,null,null);
    }
}

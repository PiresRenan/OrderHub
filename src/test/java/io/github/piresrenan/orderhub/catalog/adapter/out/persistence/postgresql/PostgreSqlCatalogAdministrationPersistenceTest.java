package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Real PostgreSQL proof that stale writes and historical evidence need durable state. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PostgreSqlCatalogAdministrationPersistenceTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void persistedRevisionRejectsASecondWriterFromTheSameProductSnapshot() {
        var tenant=UUID.randomUUID(); var id=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.products(tenant_id,id,name,slug,status) VALUES (?,?,'Product','revision-product','DRAFT')",tenant,id);
        assertThat(jdbc.queryForObject("SELECT revision FROM catalog.products WHERE tenant_id=? AND id=?",Long.class,tenant,id)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE catalog.products SET name='first',revision=revision+1 WHERE tenant_id=? AND id=? AND revision=1",tenant,id)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE catalog.products SET name='stale',revision=revision+1 WHERE tenant_id=? AND id=? AND revision=1",tenant,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM catalog.products WHERE tenant_id=? AND id=?",String.class,tenant,id)).isEqualTo("first");
    }

    @Test
    void variantRevisionIsIndependentlyPersistent() {
        var tenant=UUID.randomUUID(); var id=UUID.randomUUID(); var variant=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.products(tenant_id,id,name,slug,status) VALUES (?,?,'Product','variant-product','DRAFT')",tenant,id);
        jdbc.update("INSERT INTO catalog.product_variants(tenant_id,id,product_id,sku) VALUES (?,?,?,'revision-sku')",tenant,variant,id);
        assertThat(jdbc.queryForObject("SELECT revision FROM catalog.product_variants WHERE tenant_id=? AND id=?",Long.class,tenant,variant)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE catalog.product_variants SET status='ACTIVE',revision=revision+1 WHERE tenant_id=? AND id=? AND revision=1",tenant,variant)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE catalog.product_variants SET status='DRAFT',revision=revision+1 WHERE tenant_id=? AND id=? AND revision=1",tenant,variant)).isZero();
    }

    @Test
    void authoritativeEvidenceRejectsUpdateAndDelete() {
        var id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO catalog.administrative_audit_events
                (id,tenant_id,actor_id,resource_id,action,before_revision,after_revision,correlation_id,occurred_at)
            VALUES (?,?,?,?,'PRODUCT_CREATED',0,1,'test-evidence',CURRENT_TIMESTAMP)
            """,id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog.administrative_audit_events SET correlation_id='changed' WHERE id=?",id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM catalog.administrative_audit_events WHERE id=?",id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}

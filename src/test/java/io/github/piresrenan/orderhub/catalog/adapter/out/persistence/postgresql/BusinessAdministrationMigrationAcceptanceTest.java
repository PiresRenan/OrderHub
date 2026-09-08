package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Why: forward migrations must preserve accepted Catalog/Inventory state and migration checksums.
 * Covers: both the integrated V32 baseline and the already published V34 checkpoint.
 * Prevents: upgrades rewriting historical identities, monetary amounts, allocations or evidence. */
@Testcontainers
class BusinessAdministrationMigrationAcceptanceTest {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres"));

    @ParameterizedTest @ValueSource(strings={"32","34"})
    void upgradesAcceptedStateWithoutRewritingHistoricalFacts(String prior) {
        var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(source).target(prior).load().migrate();
        var jdbc=new JdbcTemplate(source);
        var checksums=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var tenant=UUID.randomUUID(); var product=UUID.randomUUID(); var variant=UUID.randomUUID(); var category=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.products(tenant_id,id,name,slug,status) VALUES (?,?,'Historical','historical','ACTIVE')",tenant,product);
        jdbc.update("INSERT INTO catalog.product_variants(tenant_id,id,product_id,sku,status) VALUES (?,?,?,'HISTORICAL','ACTIVE')",tenant,variant,product);
        jdbc.update("INSERT INTO catalog.categories(tenant_id,id,name,slug) VALUES (?,?,'Historical','historical')",tenant,category);
        jdbc.update("INSERT INTO catalog.product_categories(tenant_id,product_id,category_id) VALUES (?,?,?)",tenant,product,category);
        jdbc.update("INSERT INTO catalog.variant_base_prices(tenant_id,variant_id,currency_code,minor_units) VALUES (?,?,'BRL',9007199254740993)",tenant,variant);
        jdbc.update("INSERT INTO inventory.tenant_policies(tenant_id,policy) VALUES (?,'ALLOW_BACKORDER')",tenant);
        jdbc.update("INSERT INTO inventory.inventory_positions(tenant_id,variant_id,on_hand,committed,backordered,safety_stock) VALUES (?,?,20,8,3,2)",tenant,variant);
        if(prior.equals("34")) {
            jdbc.update("""
                    INSERT INTO catalog.administrative_audit_events(id,tenant_id,actor_id,resource_id,action,before_revision,after_revision,correlation_id,occurred_at)
                    VALUES (?,?,?,?,'PRODUCT_CREATED',0,1,'historical-evidence',CURRENT_TIMESTAMP)
                    """,UUID.randomUUID(),tenant,UUID.randomUUID(),product);
        }
        var latest=Flyway.configure().dataSource(source).load(); latest.migrate(); latest.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"))
                .containsAll(checksums);
        assertThat(jdbc.queryForObject("SELECT revision FROM catalog.products WHERE tenant_id=? AND id=?",Long.class,tenant,product)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM catalog.categories WHERE tenant_id=? AND id=?",Long.class,tenant,category)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT minor_units FROM catalog.variant_base_prices WHERE tenant_id=? AND variant_id=?",Long.class,tenant,variant)).isEqualTo(9007199254740993L);
        assertThat(jdbc.queryForMap("SELECT on_hand,committed,backordered,safety_stock FROM inventory.inventory_positions WHERE tenant_id=? AND variant_id=?",tenant,variant))
                .containsEntry("on_hand",20L).containsEntry("committed",8L).containsEntry("backordered",3L).containsEntry("safety_stock",2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.administrative_audit_events",Integer.class)).isEqualTo(prior.equals("34")?1:0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.movements",Integer.class)).isZero();
        assertThatThrownBy(()->jdbc.execute("TRUNCATE catalog.administrative_audit_events"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}

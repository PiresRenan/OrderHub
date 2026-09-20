# Runtime configuration reference

This reference describes the committed OrderHub configuration and its explicit
Java binding contracts. Values are operational safeguards, not load-derived
capacity claims. The source of shared defaults is
[`application.properties`](../../src/main/resources/application.properties);
[`application-dev.properties`](../../src/main/resources/application-dev.properties)
changes documentation exposure only.

In the tables, **policy** means a nonsecret application safeguard, **environment**
means deployment-specific configuration that can reveal infrastructure or trust
topology, and **secret** means a value that must never be committed or logged.
A default in the shared file satisfies a required Java binding; removing that
file/property is different from leaving an ordinary deployment override unset.

Use an environment-owned Spring configuration file or the existing environment
contracts below. Preserve duration units (`ms`, `s`, `m`, `h`, `d`) explicitly.
Review the effective configuration of the artifact being deployed; rendering a
Compose file does not prove that the application consumed a value.

## Database, authentication and environment inputs

| Property | Type / classification | Required / default | Validity and failure behavior |
| --- | --- | --- | --- |
| `spring.datasource.url` | JDBC URL / environment | Required; no product default | Must reach the intended PostgreSQL database; missing/invalid configuration or failed migration prevents startup. |
| `spring.datasource.username` | String / environment | Required runtime contract; no default | Database authentication must succeed. |
| `spring.datasource.password` | String / secret | Required runtime contract; no default | Supply through environment secret management; authentication failure is not a fallback to another database. |
| `spring.flyway.locations` | Location list / policy | `classpath:db/migration,classpath:db/baseline` | Keep both runtime locations. The versioned-only location is deliberate in historical migration tests, not the deployment configuration. |
| `orderhub.security.jwt.issuer` | Exact string / environment | Required; no default | Null/blank rejects startup. Identity matching preserves the supplied value exactly. |
| `orderhub.security.jwt.audience` | Exact string / environment | Required; no default | Null/blank rejects startup; tokens for another audience are rejected. |
| `orderhub.security.jwt.jwk-set-uri` | URI string / environment | Required; no default | Null/blank rejects startup. Configure a trusted, reachable JWK endpoint. A nonblank value alone does not prove reachability. |
| `orderhub.security.jwt.token-profile` | `GENERIC` / `COGNITO` | `GENERIC` | Cognito requires access purpose and HTTPS resource audience. Environment: `ORDERHUB_SECURITY_JWT_TOKEN_PROFILE`. Invalid enum values reject binding. Every profile requires expiry. |
| `orderhub.security.jwt.additional-issuers[n].token-profile` | `GENERIC` / `COGNITO` | `GENERIC` per issuer | Mixed provider migration keeps independent policies. Indexed environment example: `ORDERHUB_SECURITY_JWT_ADDITIONALISSUERS_0_TOKENPROFILE`. |
| `orderhub.security.cors.allowed-origins` | Exact origin list | Empty | Environment: `ORDERHUB_SECURITY_CORS_ALLOWED_ORIGINS`, comma-separated. HTTPS or literal HTTP loopback only; no path, wildcard, userinfo, query, fragment or duplicates. |
| `orderhub.security.jwt.additional-issuers[n].issuer` | Indexed string / environment | Optional list; empty by default | Each configured provider requires both fields. Duplicate issuers, including the primary issuer, reject composition. |
| `orderhub.security.jwt.additional-issuers[n].jwk-set-uri` | Indexed URI string / environment | Required for each additional issuer | No discovery or caller-selected endpoint; each decoder shares the primary audience policy. |
| `spring.profiles.active` | Profile list / environment | No product production profile is required | `dev` enables documentation properties. Anonymous documentation also requires absence of `prod`, `production`, `staging` and `pre-release`. |
| `server.port` | Port / environment | Application/container contract: `8080` | Keep Service/container mappings aligned if changed. |
| `server.address` | Bind address / environment | Shared file leaves framework binding unchanged | Compose publishes HTTP on host loopback. The explicit development launcher forces `127.0.0.1`. |

Sources: [JWT properties](../../src/main/java/io/github/piresrenan/orderhub/security/adapter/in/authentication/jwt/JwtResourceServerProperties.java),
[provider overlap](../../src/main/java/io/github/piresrenan/orderhub/security/adapter/in/authentication/jwt/AdditionalJwtTrustProperties.java),
[Security composition](../../src/main/java/io/github/piresrenan/orderhub/security/SecurityConfiguration.java)
and [migration procedure](migrations.md).

The committed launcher contracts are:

| Surface | Inputs / behavior |
| --- | --- |
| Packaged application | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`; `ORDERHUB_SECURITY_JWT_ISSUER`, `ORDERHUB_SECURITY_JWT_AUDIENCE`, `ORDERHUB_SECURITY_JWT_JWK_SET_URI`. |
| Compose | Requires `ORDERHUB_DB_NAME`, `ORDERHUB_DB_USER`, `ORDERHUB_DB_PASSWORD` and the three JWT variables; constructs datasource settings for the internal `postgres` service. `ORDERHUB_HTTP_PORT` defaults to `8080`. |
| Compose image metadata | Optional `ORDERHUB_APP_VERSION=1.0.0` and `ORDERHUB_VCS_REF=development` supply build labels; they do not select an application feature or release. |
| Kubernetes | Required `orderhub-database` Secret keys `url`, `username`, `password`; required `orderhub-security` ConfigMap containing the JWT environment variables; `orderhub-runtime-config` supplies parser/Orders limits. |
| Disposable development launcher | Owns a new PostgreSQL Testcontainer and ephemeral loopback issuer; forces their connection/trust values. It does not consume `.env` or connect to an operator database. |

The [synthetic `.env.example`](../../.env.example) documents the Compose contract.
Synthetic health-smoke JWT settings are not a usable external identity provider.
Use the [development launcher](../development/local-runtime.md) for authenticated
business examples. Do not put credentials inside a JDBC URL or run unrestricted
configuration dumps when collecting incident evidence. Compose forwards the
optional token-profile and CORS variables; Kubernetes loads them from the existing
`orderhub-security` ConfigMap. See [Cognito and frontend integration](../integration/README.md).

## HTTP and JSON safeguards

All entries in this section are **policy**, supplied in the shared configuration.

| Property | Type / default | Accepted values and effect |
| --- | --- | --- |
| `spring.jackson.deserialization.fail-on-unknown-properties` | Boolean / `true` | Unknown JSON fields are rejected. Keep `true` for the admitted contract. |
| `spring.jackson.deserialization.accept-float-as-int` | Boolean / `false` | Prevents floating-point coercion into integral Java fields; exact numeric adapter conversion has its own checks. |
| `spring.jackson.read.strict-duplicate-detection` | Boolean / `true` | Repeated JSON object property names are rejected. |
| `spring.mvc.problemdetails.enabled` | Boolean / `true` | Enables supported Spring MVC Problem Details; owner/security adapters retain their specific error contracts. |
| `spring.jackson.factory.constraints.read.max-document-length` | Long / `1048576` | Technical stream-length limit, 1 MiB for the byte-backed HTTP parser. Use a positive value. Alias: `ORDERHUB_JSON_MAX_DOCUMENT_LENGTH`. |
| `spring.jackson.factory.constraints.read.max-nesting-depth` | Integer / `64` | Maximum JSON object/array depth. Negative values reject builder configuration; zero permits no nested structures. Use a positive operational limit. Alias: `ORDERHUB_JSON_MAX_NESTING_DEPTH`. |
| `spring.jackson.factory.constraints.read.max-token-count` | Long / `100000` | Technical parser-token cap. Use a positive value. Alias: `ORDERHUB_JSON_MAX_TOKEN_COUNT`. |
| `orderhub.orders.http.max-items` | Integer / `1000` | Required by the controller; at least 1. More items return 413. Environment contract: `ORDERHUB_ORDERS_HTTP_MAX_ITEMS`. |

The three `ORDERHUB_JSON_*` variables are explicit placeholders in the shared
Jackson properties, so the Kubernetes values reach the actual parser. Canonical
Spring property overrides can still replace the whole property; do not configure
both forms with conflicting values.

Jackson 3.1.5 accepts a nonpositive document-length or token-count setting as
unlimited. OrderHub does not add a separate validator to reject that framework
behavior. Keep these limits positive; a successful startup is not proof that a
chosen override retains a cap. Parser limits are not a universal request-body
firewall, a commercial maximum, or an SLA. JSON streams and individual endpoints
also have different validation/error semantics.

## Transaction and proof lifetimes

These values are **policy**. Required fields already have the listed shared-file
defaults unless the table explicitly identifies a Java fallback.

| Property | Type / default | Bounds and failure behavior |
| --- | --- | --- |
| `orderhub.orders.transaction.timeout` | Duration / `5s` | Required; positive whole seconds, at most `2147483647s`. Invalid values reject binding/composition. Bounds the Order application transaction. |
| `orderhub.catalog.category-hierarchy.transaction.timeout` | Duration / `5s` | Same positive whole-second/int bounds; controls Category hierarchy mutation transactions. |
| `orderhub.orders.idempotency.acquisition-timeout` | Duration / `500ms` | Required; must convert to at least 1 millisecond without long overflow. Submillisecond fractions are truncated by `Duration.toMillis`. PostgreSQL also has its own supported `lock_timeout` range; very large Java-valid values need not be usable by the database. |
| `orderhub.catalog.administration.transaction-timeout-seconds` | Integer seconds / Java fallback `5` | At least 1; smaller values reject composition. |
| `orderhub.inventory.administration.transaction-timeout-seconds` | Integer seconds / Java fallback `5` | At least 1; smaller values reject composition. |
| `orderhub.workforce.staff-provisioning.intent-ttl` | Duration / `30m` | Required, positive; null/zero/negative rejects composition. No additional upper policy bound is implemented. |

The idempotency timeout applies only while claiming a durable create-Order key;
the adapter restores the previous PostgreSQL lock timeout before Catalog and
Inventory work. It is separate from the 5-second transaction limit and from
connection-pool waiting. Keep the acquisition window shorter than the total
transaction budget; the application does not enforce that cross-property
relationship.

Customer-account and external-identity link proofs expire after **15 minutes**,
set using PostgreSQL time in their repositories. They do not have configuration
keys. Several identity/membership/Staff/Customer composition boundaries use a
fixed **15-second** transaction timeout; the general Workforce audit/mutation
executor has no explicit timeout override. Do not infer one global timeout for
all routes. See the [security guide](../security/README.md) for proof semantics.

Binding authority: [Order timeout](../../src/main/java/io/github/piresrenan/orderhub/orders/config/OrderTransactionProperties.java),
[Category timeout](../../src/main/java/io/github/piresrenan/orderhub/catalog/config/CatalogCategoryHierarchyTransactionProperties.java),
[idempotency adapter](../../src/main/java/io/github/piresrenan/orderhub/orders/adapter/out/persistence/postgresql/PostgreSqlCreateOrderIdempotencyRepository.java)
and [Staff TTL](../../src/main/java/io/github/piresrenan/orderhub/workforce/config/StaffProvisioningProperties.java).

## Health and graceful shutdown

These are **policy** values. The operationally admitted values are narrower than
the full set Spring Boot supports.

| Property | Type / committed value | Operational consequence |
| --- | --- | --- |
| `management.endpoints.web.exposure.include` | Endpoint list / `health` | No HTTP metrics, environment, beans, loggers, shutdown or publication-recovery endpoint is exposed. |
| `management.endpoint.health.show-details` | Enum / `never` | Hides component topology and dependency details. |
| `management.endpoint.health.probes.enabled` | Boolean / `true` | Enables application availability probe groups. |
| `management.endpoint.health.group.readiness.include` | Contributor list / `readinessState,db` | Database failure makes the instance unready. |
| `management.endpoint.health.probes.add-additional-paths` | Boolean / `true` | `/livez` and `/readyz` share the application HTTP connector. |
| `spring.lifecycle.timeout-per-shutdown-phase` | Duration / `25s` | Budget per shutdown phase; Compose and Kubernetes allow 30 seconds before forced termination. |

The public allowlist is exactly `/livez`, `/readyz`, `/actuator/health`, plus the
separate documentation paths only in the admitted development profile. It is
not `/actuator/**`. Changing Actuator exposure alone does not widen that
security allowlist. Deployment probe intervals and resources are documented in
the [operations guide](README.md).

## Publication lifecycle and analytical housekeeping

All values are **policy**; the retention window is an environment-owned policy
decision, not a credential or a supplied legal duration.

| Property | Type / default | Bounds and failure behavior |
| --- | --- | --- |
| `spring.modulith.events.jdbc.schema-initialization.enabled` | Boolean / `false` | Keep false: Flyway owns the publication relation. |
| `spring.modulith.events.jdbc.schema` | Schema name / `public` | Matches V23/B44; do not redirect to another schema or mutable search path. |
| `spring.modulith.events.completion-mode` | Enum / `delete` | Successful publications are deleted; incomplete/failed ones remain recoverable. |
| `spring.modulith.events.republish-outstanding-events-on-restart` | Boolean / `true` | Startup republishes outstanding work at least once; no periodic recovery job or remote replay endpoint exists. |
| `orderhub.analytics.housekeeping.enabled` | Boolean / `false` | Disabled unless explicitly chosen. Enabling requires a valid retention window. |
| `orderhub.analytics.housekeeping.retention-window` | Duration / absent | Positive when enabled; ignored while disabled. Very large positive values can exceed `Instant` arithmetic at execution and fail before deleting/projecting. |
| `orderhub.analytics.housekeeping.batch-size` | Integer / `100` | Inclusive 1–1000; invalid binding rejects startup. Checked even when disabled. |
| `orderhub.analytics.housekeeping.fixed-delay` | Duration / `1h` | Required positive delay; null/zero/negative rejects startup. Checked even when disabled. One invocation performs one bounded batch. |

The same retention policy excludes already-expired replay before analytical
subject mapping or fact persistence. Disablement stops scheduled cleanup and
expiry suppression; it does not restore deleted facts. Configure the same
retention policy on every replica. See [housekeeping operations](README.md#analytical-housekeeping)
and [binding validation](../../src/main/java/io/github/piresrenan/orderhub/analytics/config/AnalyticsHousekeepingProperties.java).

## Generated API documentation

These nonsecret **policy** controls govern documentation, not business access.
Keep the committed values unless deliberately reviewing the resulting exposure.

| Property | Type / shared default | Meaning |
| --- | --- | --- |
| `springdoc.api-docs.enabled` | Boolean / `false` | Generates/exposes `/v3/api-docs` only when enabled; `dev` sets true. |
| `springdoc.swagger-ui.enabled` | Boolean / `false` | Exposes Swagger UI assets only when enabled; `dev` sets true. |
| `springdoc.api-docs.version` | Enum / `OPENAPI_3_1` | Generates OpenAPI 3.1. |
| `springdoc.show-actuator` | Boolean / `false` | Keeps operational endpoints outside the generated business contract. |
| `springdoc.override-with-generic-response` | Boolean / `false` | Avoids automatically attributing every generic handler response to every operation. |
| `springdoc.writer-with-order-by-keys` | Boolean / `true` | Orders object keys for stable export/review. |
| `springdoc.swagger-ui.persist-authorization` | Boolean / `false` | Does not persist supplied bearer authorization across browser reloads. |
| `springdoc.swagger-ui.validator-url` | String / empty | No external Swagger validator configured. |
| `springdoc.swagger-ui.disable-swagger-default-url` | Boolean / `true` | Disables the example/default Swagger definition. |
| `springdoc.swagger-ui.tags-sorter` | Sort mode / `alpha` | Alphabetic tags. |
| `springdoc.swagger-ui.operations-sorter` | Sort mode / `alpha` | Alphabetic operations. |
| `springdoc.swagger-ui.doc-expansion` | Display mode / `none` | Collapsed operation groups. |

| Runtime | Default documentation | Authentication if documentation is enabled |
| --- | --- | --- |
| No profile, `prod`, `production`, `staging`, `pre-release` | Disabled | Ordinary internal-user bearer authentication. |
| `dev` alone | Enabled | Anonymous documentation only; business routes retain normal authentication. |
| `dev` combined with any excluded environment profile | `dev` properties may still enable docs | No anonymous documentation filter; normal bearer boundary remains. |

Profiles are not a sandbox. Do not deploy a production environment with `dev` to
obtain a documentation UI. When docs are deliberately enabled outside dev, an
unauthenticated browser cannot fetch the protected HTML/schema as though it had
already completed Swagger's in-page authorization flow. Prefer the approved
contract artifact or an authenticated client.

## Logging and inherited infrastructure defaults

The five committed logger overrides are **policy**:

| Property | Value | Purpose |
| --- | --- | --- |
| `logging.level.org.springframework.boot.jdbc.health.DataSourceHealthIndicator` | `ERROR` | Suppresses expected health JDBC exception chains. |
| `logging.level.com.zaxxer.hikari.pool.PoolBase` | `ERROR` | Suppresses connection-validation implementation details. |
| `logging.level.com.zaxxer.hikari.pool.HikariPool` | `WARN` | Avoids ordinary concrete connection startup details. |
| `logging.level.org.flywaydb.core.FlywayExecutor` | `WARN` | Avoids the startup JDBC URL discovery log. |
| `logging.level.com.zaxxer.hikari.pool.ProxyConnection` | `ERROR` | Suppresses broken-connection cleanup exception chains. |

OrderHub does not override Hikari pool sizing, Tomcat thread/connection sizing
or Nimbus key-fetch transport. The following are **resolved dependency
defaults**, verified from HikariCP 7.0.2 `HikariConfig`, Spring Boot 4.1.1 Tomcat
configuration metadata, and Spring Security 7.1.1
`NimbusJwtDecoder`/`JwtDecoderProviderConfigurationUtils`. They are not committed
OrderHub tuning or tested production capacity.

| Inherited setting | Default without environment/JVM override | Interpretation |
| --- | --- | --- |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Per application pool, not across all replicas. |
| `spring.datasource.hikari.minimum-idle` | Maximum pool size | Defaults to the fixed-size pool behavior. |
| `spring.datasource.hikari.connection-timeout` | `30000` milliseconds | Wait for a pool connection; not the Order transaction timeout. |
| `spring.datasource.hikari.validation-timeout` | `5000` milliseconds | Pool connection validation. |
| `spring.datasource.hikari.idle-timeout` | `600000` milliseconds | Does not retire below minimum idle; no shrinking at the default fixed size. |
| `spring.datasource.hikari.max-lifetime` | `1800000` milliseconds | Connection lifetime policy. |
| `spring.datasource.hikari.keepalive-time` | `120000` milliseconds | Pool keepalive policy. |
| `server.tomcat.threads.max` / `.min-spare` | `200` / `10` | Platform-thread worker settings; not applicable if virtual threads are enabled. |
| `server.tomcat.max-connections` / `.accept-count` | `8192` / `100` | Connector connections and accept backlog. |
| `server.tomcat.accesslog.enabled` | `false` | No access log enabled by the application. |
| Nimbus JWK HTTP connect / read timeout | `30000` milliseconds each | Framework transport reads JVM `sun.net.client.defaultConnectTimeout` and `sun.net.client.defaultReadTimeout` when supplied. |

The repository does not set `server.tomcat.connection-timeout`; no application
value is claimed for it. There is no custom JWK cache policy, metrics exporter,
trace exporter, permissive CORS defaults or body logging. The bounded
[qualification experiment](release-qualification.md) measures pool contention
and recovery without establishing production capacity. Use the
[operations procedure](README.md) to assess these boundaries
before changing inherited defaults. Framework setters may reject or normalize
invalid tuning values; they are not a replacement for checking the effective
runtime configuration.

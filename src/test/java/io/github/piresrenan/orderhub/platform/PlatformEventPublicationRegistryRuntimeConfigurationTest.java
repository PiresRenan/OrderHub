package io.github.piresrenan.orderhub.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PlatformEventPublicationRegistryRuntimeConfigurationTest {

    private static final String EVENT_PUBLICATION_REGISTRY_TYPE =
            "org.springframework.modulith.events.core.EventPublicationRegistry";

    private static final String JDBC_AUTO_CONFIGURATION_TYPE =
            "org.springframework.modulith.events.jdbc"
                    + ".JdbcEventPublicationAutoConfiguration";

    private static final String JACKSON_SERIALIZER_TYPE =
            "org.springframework.modulith.events.jackson.JacksonEventSerializer";

    private static final String REGISTRY_BEAN =
            "eventPublicationRegistry";

    private static final String JDBC_REPOSITORY_BEAN =
            "jdbcEventPublicationRepository";

    private static final String JDBC_SETTINGS_BEAN =
            "jdbcEventPublicationRepositorySettings";

    private static final String JACKSON_SERIALIZER_BEAN =
            "jacksonEventSerializer";

    private static final String SCHEMA_INITIALIZER_BEAN =
            "databaseSchemaInitializer";

    private static final String SCHEMA_INITIALIZATION_PROPERTY =
            "spring.modulith.events.jdbc.schema-initialization.enabled";

    private static final String JDBC_SCHEMA_PROPERTY =
            "spring.modulith.events.jdbc.schema";

    private static final String COMPLETION_MODE_PROPERTY =
            "spring.modulith.events.completion-mode";

    private static final String RESTART_REPUBLICATION_PROPERTY =
            "spring.modulith.events.republish-outstanding-events-on-restart";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void activatesFlywayOwnedJdbcEventPublicationRegistryWithExplicitLifecyclePolicy() {
        // Why: ADR-0014 selected Spring Modulith's persistent JDBC Event
        // Publication Registry, but a selection only becomes real when the
        // application actually boots it against the relation Flyway owns, with
        // the lifecycle policy the decision fixed. Left at framework defaults
        // the registry would create its own schema, retain completed
        // publications and resolve its table through a mutable search_path.
        // Covers: the registry and its JDBC auto-configuration being on the
        // classpath, exactly one registry bean backed by the JDBC publication
        // repository, the framework's own Jackson 3 serializer, framework
        // schema creation switched off, the publication table pinned to the
        // public schema, DELETE completion reaching the JDBC settings, and
        // restart republication left disabled.
        // Prevents: a registry that silently owns its schema, an unqualified
        // table name, completed publications accumulating, and restart-driven
        // republication in a multi-instance deployment.
        //
        // The framework packages are referenced only as version-pinned name
        // strings and bean names. Importing them would make this test fail to
        // compile before the starter exists, which would prove only a missing
        // compile-time type rather than the runtime contract above.

        var registryBeanNames =
                beanNamesFor(
                        EVENT_PUBLICATION_REGISTRY_TYPE);

        var jdbcSettings =
                beanIfPresent(
                        JDBC_SETTINGS_BEAN);

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(
                        classPresent(
                                EVENT_PUBLICATION_REGISTRY_TYPE))
                        .as("The event publication registry must be on the"
                                + " runtime classpath")
                        .isTrue(),

                () -> assertThat(
                        classPresent(
                                JDBC_AUTO_CONFIGURATION_TYPE))
                        .as("The JDBC registry auto-configuration must be on"
                                + " the runtime classpath")
                        .isTrue(),

                () -> assertThat(registryBeanNames)
                        .as("Exactly one event publication registry must be"
                                + " active")
                        .containsExactly(
                                REGISTRY_BEAN),

                () -> assertThat(
                        applicationContext.containsBean(
                                JDBC_REPOSITORY_BEAN))
                        .as("The registry must be backed by the JDBC"
                                + " publication repository, not merely by event"
                                + " API classes on the classpath")
                        .isTrue(),

                () -> assertThat(
                        applicationContext.containsBean(
                                JACKSON_SERIALIZER_BEAN))
                        .as("The starter's default event serializer must be"
                                + " active")
                        .isTrue(),

                () -> assertThat(
                        beanClassName(
                                JACKSON_SERIALIZER_BEAN))
                        .as("Event serialization must use the framework's own"
                                + " Jackson serializer rather than a local"
                                + " substitute")
                        .isEqualTo(
                                JACKSON_SERIALIZER_TYPE),

                () -> assertThat(
                        applicationContext.containsBean(
                                SCHEMA_INITIALIZER_BEAN))
                        .as("Flyway owns the publication schema, so the"
                                + " framework initializer must not be created")
                        .isFalse(),

                () -> assertThat(
                        environment.getProperty(
                                SCHEMA_INITIALIZATION_PROPERTY))
                        .as("Framework schema initialization must be disabled"
                                + " explicitly rather than left to its default")
                        .isEqualTo("false"),

                () -> assertThat(
                        environment.getProperty(
                                JDBC_SCHEMA_PROPERTY))
                        .as("The publication table must be pinned to the schema"
                                + " the migration owns")
                        .isEqualTo("public"),

                () -> assertThat(
                        readNoArgument(
                                jdbcSettings,
                                "getSchema"))
                        .as("The configured schema must reach the JDBC"
                                + " repository settings, so the table is never"
                                + " resolved through a mutable search_path")
                        .isEqualTo("public"),

                () -> assertThat(
                        environment.getProperty(
                                COMPLETION_MODE_PROPERTY))
                        .as("Completed publications must be deleted rather than"
                                + " retained")
                        .isEqualTo("delete"),

                () -> assertThat(
                        readNoArgument(
                                jdbcSettings,
                                "isDeleteCompletion"))
                        .as("The JDBC repository settings must apply DELETE"
                                + " completion")
                        .isEqualTo(Boolean.TRUE),

                () -> assertThat(
                        readNoArgument(
                                jdbcSettings,
                                "isUpdateCompletion"))
                        .as("UPDATE completion must not remain in effect")
                        .isEqualTo(Boolean.FALSE),

                () -> assertThat(
                        readNoArgument(
                                jdbcSettings,
                                "isArchiveCompletion"))
                        .as("ARCHIVE completion must not be in effect")
                        .isEqualTo(Boolean.FALSE),

                () -> assertThat(
                        environment.getProperty(
                                RESTART_REPUBLICATION_PROPERTY))
                        .as("Restart republication must stay disabled"
                                + " explicitly, because recovery is controlled"
                                + " and multi-instance restarts are ambiguous")
                        .isEqualTo("false"));
    }

    /**
     * Reports whether a version-pinned framework type is on the runtime
     * classpath, without resolving it.
     */
    private boolean classPresent(
            String className) {

        return ClassUtils.isPresent(
                className,
                getClass().getClassLoader());
    }

    /**
     * Returns the bean names of a framework type, or none when that type is
     * absent, so absence surfaces as an assertion failure rather than a
     * class-loading error.
     */
    private String[] beanNamesFor(
            String className) {

        var type =
                typeIfPresent(
                        className);

        return type == null
                ? new String[0]
                : applicationContext.getBeanNamesForType(type);
    }

    private Class<?> typeIfPresent(
            String className) {

        var classLoader =
                getClass().getClassLoader();

        if (!ClassUtils.isPresent(
                className,
                classLoader)) {

            return null;
        }

        try {
            return ClassUtils.forName(
                    className,
                    classLoader);

        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    private Object beanIfPresent(
            String beanName) {

        return applicationContext.containsBean(beanName)
                ? applicationContext.getBean(beanName)
                : null;
    }

    private String beanClassName(
            String beanName) {

        var bean =
                beanIfPresent(beanName);

        return bean == null
                ? null
                : bean.getClass().getName();
    }

    /**
     * Reads one public no-argument accessor of a framework bean.
     *
     * <p>
     * Only public API is used. An absent bean or accessor yields {@code null},
     * so the missing runtime surfaces through the assertion rather than through
     * a reflective error.
     * </p>
     */
    private static Object readNoArgument(
            Object target,
            String methodName) {

        if (target == null) {
            return null;
        }

        try {
            return target.getClass()
                    .getMethod(methodName)
                    .invoke(target);

        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }
}

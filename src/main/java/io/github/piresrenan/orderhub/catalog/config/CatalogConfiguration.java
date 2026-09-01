package io.github.piresrenan.orderhub.catalog.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql.PostgreSqlCatalogOrderabilityRepository;
import io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql.PostgreSqlCategoryRepository;
import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCategoryHierarchyMutationExecutor;
import io.github.piresrenan.orderhub.catalog.application.port.in.SaveCategoryUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogOrderabilityRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryHierarchyMutationExecutor;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryRepository;
import io.github.piresrenan.orderhub.catalog.application.service.SaveCategoryService;
import io.github.piresrenan.orderhub.catalog.application.service.ValidateOrderableVariantsService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        CatalogCategoryHierarchyTransactionProperties.class)
public class CatalogConfiguration {

    @Bean
    CategoryRepository categoryRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlCategoryRepository(
                jdbcTemplate);
    }

    @Bean
    CategoryHierarchyMutationExecutor categoryHierarchyMutationExecutor(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            CatalogCategoryHierarchyTransactionProperties properties) {

        var transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        transactionTemplate.setTimeout(
                properties.timeoutSeconds());

        return new PostgreSqlCategoryHierarchyMutationExecutor(
                jdbcTemplate,
                transactionTemplate);
    }

    @Bean
    SaveCategoryUseCase saveCategoryUseCase(
            CategoryRepository categoryRepository,
            CategoryHierarchyMutationExecutor mutationExecutor) {

        return new SaveCategoryService(
                categoryRepository,
                mutationExecutor);
    }

    @Bean
    CatalogOrderabilityRepository catalogOrderabilityRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlCatalogOrderabilityRepository(
                jdbcTemplate);
    }

    @Bean
    ValidateOrderableVariantsUseCase validateOrderableVariantsUseCase(
            CatalogOrderabilityRepository repository) {

        return new ValidateOrderableVariantsService(
                repository);
    }
}

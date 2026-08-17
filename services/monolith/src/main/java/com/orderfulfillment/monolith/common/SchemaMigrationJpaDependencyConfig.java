package com.orderfulfillment.monolith.common;

import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Forces entityManagerFactory to be created after {@link SchemaMigrationRunner} has migrated every schema. */
@Configuration
class SchemaMigrationJpaDependencyConfig {

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor schemaMigratorDependsOnPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor("schemaMigrator");
    }
}

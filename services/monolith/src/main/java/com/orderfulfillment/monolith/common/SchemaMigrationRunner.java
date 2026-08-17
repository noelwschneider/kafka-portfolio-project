package com.orderfulfillment.monolith.common;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

/**
 * docs/db-ownership.md requires one schema and one Flyway migration history per owning service,
 * even though this phase runs all four inside a single application and database server. Spring
 * Boot's built-in Flyway auto-configuration only drives a single schema/history, so this runs one
 * independent Flyway instance per domain schema instead (spring.flyway.enabled=false disables the
 * single-schema default).
 *
 * <p>Migration must happen in @PostConstruct, not a CommandLineRunner: Hibernate's
 * ddl-auto=validate check runs while the entityManagerFactory bean is created during context
 * refresh, which is earlier than CommandLineRunners execute. SchemaMigrationJpaDependencyConfig
 * makes entityManagerFactory depend on this bean so the migration is guaranteed to run first.
 */
@Component("schemaMigrator")
public class SchemaMigrationRunner {

    private final DataSource dataSource;

    public SchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrateAllSchemas() {
        migrate("order_service", "classpath:db/migration/order");
        migrate("inventory_service", "classpath:db/migration/inventory");
        migrate("payment_service", "classpath:db/migration/payment");
        migrate("fulfillment_service", "classpath:db/migration/fulfillment");
    }

    private void migrate(String schema, String location) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .createSchemas(true)
                .locations(location)
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }
}

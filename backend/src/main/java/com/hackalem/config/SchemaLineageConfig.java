package com.hackalem.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Both independently released V3 histories converge at V4 without rewriting history or data. */
@Configuration
public class SchemaLineageConfig {
    @Bean
    FlywayConfigurationCustomizer schemaLineage(DataSource dataSource) {
        return configuration -> configuration.locations("classpath:db/migration", location(dataSource));
    }

    static String location(DataSource dataSource) {
        String lineage = "identity";
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var exists = statement.executeQuery("SELECT to_regclass('flyway_schema_history') IS NOT NULL")) {
                exists.next();
                if (!exists.getBoolean(1)) return "classpath:db/baseline-identity";
            }
            try (var rows = statement.executeQuery("SELECT description FROM flyway_schema_history WHERE version='3' AND success")) {
                if (rows.next()) {
                    lineage = switch (rows.getString(1)) {
                        case "identity chat cart" -> "identity";
                        case "catalog versions offers stock" -> "catalog";
                        default -> throw new IllegalStateException("Unknown Flyway V3 lineage; refusing to change existing history");
                    };
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect Flyway schema lineage", e);
        }
        return "classpath:db/baseline-" + lineage;
    }
}

package com.hackalem.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaLineageConfigTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @ParameterizedTest @ValueSource(strings={"identity","catalog"})
    void preservesEachReleasedV3AndItsRows(String lineage) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        String database="lineage_"+lineage;
        admin.execute("CREATE DATABASE "+database);
        var source=new DriverManagerDataSource(postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+database),postgres.getUsername(),postgres.getPassword());
        var db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("2").load().migrate();
        db.update("INSERT INTO products(id,article,name,stock,search_text) VALUES (42,'000042','Legacy',true,'Legacy')");
        var before=Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/baseline-"+lineage).target("3").load();
        before.migrate();
        var history=db.queryForMap("SELECT description, checksum FROM flyway_schema_history WHERE version='3'");
        assertThat(SchemaLineageConfig.location(source)).isEqualTo("classpath:db/baseline-"+lineage);
        var after=Flyway.configure().dataSource(source).locations("classpath:db/migration",SchemaLineageConfig.location(source)).target("5").load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(after.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForMap("SELECT description, checksum FROM flyway_schema_history WHERE version='3'")).isEqualTo(history);
        assertThat(db.queryForObject("SELECT article FROM products WHERE id=42",String.class)).isEqualTo("000042");
        assertThat(db.queryForObject("SELECT count(*) FROM visitor_sessions",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_legacy_products WHERE product_id=42",Integer.class)).isEqualTo(1);
    }
}

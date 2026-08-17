package ao.creativemode.kixi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FlywayMigrationConfigTest {

    @Test
    void convertsPostgresR2dbcUrlToJdbcUrl() {
        assertEquals(
                "jdbc:postgresql://postgres:5432/kixi",
                FlywayMigrationConfig.toJdbcUrl("r2dbc:postgresql://postgres:5432/kixi")
        );
    }

    @Test
    void preservesExplicitJdbcUrl() {
        assertEquals(
                "jdbc:postgresql://localhost:5432/kixi",
                FlywayMigrationConfig.toJdbcUrl("jdbc:postgresql://localhost:5432/kixi")
        );
    }
}

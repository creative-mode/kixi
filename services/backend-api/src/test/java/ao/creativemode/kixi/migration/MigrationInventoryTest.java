package ao.creativemode.kixi.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class MigrationInventoryTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V(\\d+)__.+\\.sql");

    @Test
    void migrationsHaveUniqueContiguousVersions() throws IOException, URISyntaxException {
        Path migrationDirectory = migrationDirectory();

        List<Integer> versions;
        try (var files = Files.list(migrationDirectory)) {
            versions = files
                    .map(path -> path.getFileName().toString())
                    .map(VERSIONED_MIGRATION::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
        }

        assertTrue(!versions.isEmpty(), "At least one Flyway migration is required");
        int latestVersion = versions.get(versions.size() - 1);
        assertEquals(
                IntStream.rangeClosed(1, latestVersion).boxed().toList(),
                versions,
                "Flyway migrations must have one contiguous version sequence"
        );
        assertTrue(latestVersion >= 18, "The current schema baseline must include V18");
    }

    @Test
    void relationalTablesUseTheRuntimePluralNames() throws IOException, URISyntaxException {
        Path migrationDirectory = migrationDirectory();

        Map<String, String> tableMigrations = Map.of(
                "V11__create_statement_table.sql", "CREATE TABLE statements",
                "V12__create_questions_table.sql", "REFERENCES statements(id)",
                "V13__create_simulation_table.sql", "CREATE TABLE simulations",
                "V16__create_simulation_answers_table.sql", "REFERENCES simulations(id)"
        );

        for (Map.Entry<String, String> entry : tableMigrations.entrySet()) {
            String sql = Files.readString(migrationDirectory.resolve(entry.getKey()));
            assertTrue(
                    sql.contains(entry.getValue()),
                    () -> entry.getKey() + " must contain: " + entry.getValue()
            );
        }
    }

    private Path migrationDirectory() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                getClass().getClassLoader().getResource("db/migration")
        ).toURI());
    }
}

package ao.creativemode.kixi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reteste F4: a migração V91 reabre o agendamento dos planos reagendados antes da correção ce19da28 (concluídos, sem enunciado numa
 * prova P1/P2 por dispensar) e só esses: não toca num plano com a pauta aberta, nem num plano bem concluído. Corre o Flyway numa base
 * nova até à V90, semeia os três casos e aplica o resto, o que prova também que as migrações aplicam de zero e a partir da V90.
 */
class ReopenSchedulingMigrationPostgresTest {

    private static final String DATABASE = "kixi_mig_v91_it";
    private static final String HOST = env("KIXI_POSTGRES_HOST", "localhost");
    private static final String PORT = env("KIXI_POSTGRES_PORT", "5433");
    private static final String USER = env("KIXI_POSTGRES_USER", "kixi");
    private static final String PASSWORD = env("KIXI_POSTGRES_PASSWORD", "kixi_secret");

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("ReopenSchedulingMigrationPostgresTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
        }
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(url(DATABASE), USER, PASSWORD).target(target).load();
    }

    @BeforeAll
    static void freshDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(url("postgres"), USER, PASSWORD); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + DATABASE);
        }
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(url("postgres"), USER, PASSWORD); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    @Test
    void reopensOnlyTheConcludedPlansWithoutAStatementAndWhoseTheSheetIsNotOpenYet() throws Exception {
        // de zero até à V90 (a anterior à nova migração)
        flyway("90").migrate();
        try (Connection db = DriverManager.getConnection(url(DATABASE), USER, PASSWORD); Statement st = db.createStatement()) {
            st.execute("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES (910001, 'mig91', 'mig91@example.com', 'x', true)");
            st.execute("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (910001, 'M91', 'Escola M91', now(), now())");
            st.execute("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (910001, 2391, 2392, now(), now())");
            st.execute("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (910001, 'C91', 'Informática', 910001, now(), now())");
            st.execute("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                    + "(910001, 'A', 12, 910001, 910001, 910001, now(), now()), (910002, 'B', 12, 910001, 910001, 910001, now(), now()), (910003, 'C', 12, 910001, 910001, 910001, now(), now()), (910004, 'D', 12, 910001, 910001, 910001, now(), now())");
            st.execute("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (910001, 'M91', 'Matemática', now(), now())");
            st.execute("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source) "
                    + "VALUES (910001, 'Prova', 'TEST', 60, 5, 910001, false, false, 'manual')");
            // 1 afectado: concluído, P2 sem enunciado e por dispensar, pauta por abrir
            // 2 não afectado: concluído com enunciado em P1 e P2
            // 3 pauta aberta: P2 sem enunciado mas a pauta já abriu
            // 4 afectado só na P1 dispensada (P2 com enunciado): não é afectado, a P1 dispensada não precisa de enunciado
            st.execute("INSERT INTO term_plans (id, class_id, subject_id, term_id, created_by, scheduling_concluded_at, pauta_opened_at) VALUES "
                    + "(1, 910001, 910001, 1, 910001, now(), NULL), (2, 910002, 910001, 1, 910001, now(), NULL), (3, 910003, 910001, 1, 910001, now(), now()), (4, 910004, 910001, 1, 910001, now(), NULL)");
            st.execute("INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date, statement_id, dispensed_at, dispensed_reason) VALUES "
                    + "(1, 'P1', 1, 'P1', current_date, 910001, NULL, NULL), (1, 'P2', 1, 'P2', current_date, NULL, NULL, NULL), "
                    + "(2, 'P1', 1, 'P1', current_date, 910001, NULL, NULL), (2, 'P2', 1, 'P2', current_date, 910001, NULL, NULL), "
                    + "(3, 'P1', 1, 'P1', current_date, 910001, NULL, NULL), (3, 'P2', 1, 'P2', current_date, NULL, NULL, NULL), "
                    + "(4, 'P1', 1, 'P1', current_date, NULL, now(), 'dispensada'), (4, 'P2', 1, 'P2', current_date, 910001, NULL, NULL)");
            assertThat(concluded(st)).containsExactly(1L, 2L, 3L, 4L);
        }

        // a V91 (e qualquer posterior) aplica-se a partir da V90
        flyway("latest").migrate();
        try (Connection db = DriverManager.getConnection(url(DATABASE), USER, PASSWORD); Statement st = db.createStatement()) {
            assertThat(concluded(st)).as("só o plano 1 reabre").containsExactly(2L, 3L, 4L);
            assertThat(scalar(st, "SELECT count(*) FROM term_plans WHERE id = 3 AND pauta_opened_at IS NOT NULL")).isEqualTo(1L);

            // idempotente: voltar a correr o SQL não altera mais nada
            st.execute("UPDATE term_plans SET scheduling_concluded_at = now() WHERE id = 1");
            String sql = Files.readString(Path.of("src/main/resources/db/migration/V91__reabrir_agendamento_sem_enunciado.sql"));
            st.execute(sql);
            assertThat(concluded(st)).containsExactly(2L, 3L, 4L);
            st.execute(sql);
            assertThat(concluded(st)).containsExactly(2L, 3L, 4L);
        }
    }

    private static java.util.List<Long> concluded(Statement st) throws SQLException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT id FROM term_plans WHERE scheduling_concluded_at IS NOT NULL ORDER BY id")) {
            while (rs.next()) ids.add(rs.getLong(1));
        }
        return ids;
    }

    private static long scalar(Statement st, String query) throws SQLException {
        try (ResultSet rs = st.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

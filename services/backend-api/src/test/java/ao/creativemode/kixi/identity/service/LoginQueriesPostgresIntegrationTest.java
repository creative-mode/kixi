package ao.creativemode.kixi.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.identity.dto.auth.LoginResponse;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * O caminho do login contra um Postgres migrado (kixi#566): a consulta única por username ou e-mail, os papéis num só
 * {@code JOIN} e a purga agendada do limitador. Os testes unitários provam que o serviço chama estas consultas; só a base
 * prova que devolvem o mesmo que as duas consultas e o {@code N+1} que substituíram.
 *
 * <p>Ids a partir de 566001, à parte dos blocos dos outros testes de Postgres.</p>
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class LoginQueriesPostgresIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String IDS = "566001, 566002, 566003, 566004";

    @Autowired AuthService auth;
    @Autowired AccountRepository accounts;
    @Autowired RoleRepository roles;
    @Autowired LoginRateLimiter limiter;
    @Autowired PasswordEncoder encoder;
    @Autowired DatabaseClient database;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String host = env("KIXI_POSTGRES_HOST", "localhost");
        String port = env("KIXI_POSTGRES_PORT", "5433");
        String name = env("KIXI_POSTGRES_DATABASE", "kixi_exam_room_it");
        String user = env("KIXI_POSTGRES_USER", "kixi");
        String password = env("KIXI_POSTGRES_PASSWORD", "kixi_secret");
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.r2dbc.username", () -> user);
        registry.add("spring.r2dbc.password", () -> password);
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    @BeforeEach
    void data() {
        clean();
        String hash = encoder.encode("Segredo-566!");
        // 566001 ana (ativa), 566002 uma conta cujo USERNAME é o e-mail da Ana, 566003 apagada, 566004 inativa
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES "
                + "(566001, 'it566-ana', 'it566-ana@kixi.test', '" + hash + "', true), "
                + "(566002, 'it566-ana@kixi.test', 'it566-outra@kixi.test', '" + hash + "', true)");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, deleted_at) VALUES "
                + "(566003, 'it566-apagada', 'it566-apagada@kixi.test', '" + hash + "', true, now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) VALUES "
                + "(566004, 'it566-inativa', 'it566-inativa@kixi.test', '" + hash + "', true, false)");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM account_roles WHERE account_id IN (" + IDS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + IDS + ")");
        sql("DELETE FROM login_rate_limits WHERE rate_key LIKE 'it566-%'");
    }

    @Test
    void theLookupFindsByUsernameAndByEmailInOneQuery() {
        assertThat(accounts.findForLogin("it566-ana", "it566-ana").block(WAIT).getId()).isEqualTo(566001L);
        assertThat(accounts.findForLogin("it566-ana@kixi.test", "it566-ana@kixi.test").block(WAIT)).isNotNull();
        assertThat(accounts.findForLogin("IT566-OUTRA@KIXI.TEST", "it566-outra@kixi.test").block(WAIT).getId())
                .as("o e-mail chega em minúsculas, como o serviço o envia").isEqualTo(566002L);
    }

    @Test
    void anExactUsernameWinsOverAnotherAccountsEmailAsTheTwoOldLookupsDid() {
        // 566002 tem como USERNAME o e-mail da conta 566001: quem escreve esse texto entra na 566002, como antes
        // (primeiro o username, só depois o e-mail).
        assertThat(accounts.findForLogin("it566-ana@kixi.test", "it566-ana@kixi.test").block(WAIT).getId())
                .isEqualTo(566002L);
    }

    @Test
    void aDeletedAccountIsNeverFound() {
        assertThat(accounts.findForLogin("it566-apagada", "it566-apagada").block(WAIT)).isNull();
        assertThat(accounts.findForLogin("it566-apagada@kixi.test", "it566-apagada@kixi.test").block(WAIT)).isNull();
        assertThat(accounts.findForLogin("nao-existe", "nao-existe").block(WAIT)).isNull();
    }

    @Test
    void theRolesComeInOneJoinInTheOrderTheyWereGrantedAndWithoutTheRemovedOnes() {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT 566001, id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT 566001, id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO account_roles (account_id, role_id, deleted_at) SELECT 566001, id, now() FROM roles WHERE name = 'ADMIN'");

        List<String> names = roles.findActiveNamesByAccountId(566001L).collectList().block(WAIT);

        assertThat(names).containsExactly("TEACHER", "STUDENT");
        assertThat(roles.findActiveNamesByAccountId(566002L).collectList().block(WAIT)).isEmpty();
    }

    @Test
    void loginWorksByUsernameAndByEmailAndKeepsRejectingTheRest() {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT 566001, id FROM roles WHERE name = 'STUDENT'");

        LoginResponse byUsername = auth.login("it566-ana", "Segredo-566!").block(WAIT);
        assertThat(byUsername.accountId()).isEqualTo(566001L);
        assertThat(byUsername.roles()).containsExactly("STUDENT");

        assertThat(auth.login("  it566-ana@kixi.test ", "Segredo-566!").block(WAIT).accountId())
                .as("a conta 566002 tem esse texto como username e ganha: ver o teste da precedência").isEqualTo(566002L);
        assertThat(auth.login("IT566-ANA@KIXI.TEST", "Segredo-566!").block(WAIT).accountId())
                .as("com maiúsculas já não é o username de ninguém: entra pelo e-mail, que se compara em minúsculas")
                .isEqualTo(566001L);
        assertThat(auth.login("it566-outra@KIXI.test", "Segredo-566!").block(WAIT).accountId()).isEqualTo(566002L);

        assertThatThrownBy(() -> auth.login("it566-ana", "errada").block(WAIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
        assertThatThrownBy(() -> auth.login("it566-apagada", "Segredo-566!").block(WAIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
        assertThatThrownBy(() -> auth.login("it566-inativa", "Segredo-566!").block(WAIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
        assertThat(database.sql("SELECT last_login FROM accounts WHERE id = 566001").map((r, m) -> r.get(0)).one().block(WAIT))
                .as("o último login ficou registado").isNotNull();
    }

    @Test
    void thePurgeDropsOnlyTheCountersOlderThanADayAndAStaleKeyStillRestartsItsWindow() {
        sql("INSERT INTO login_rate_limits (rate_key, window_started_at, attempts, updated_at) VALUES "
                + "('it566-velho', now() - interval '3 days', 99, now() - interval '3 days'), "
                + "('it566-recente', now(), 3, now())");

        limiter.purgeExpiredEntries();

        assertThat(count("SELECT count(*) FROM login_rate_limits WHERE rate_key = 'it566-velho'")).isZero();
        assertThat(count("SELECT count(*) FROM login_rate_limits WHERE rate_key = 'it566-recente'")).isEqualTo(1);

        // Sem a limpeza no caminho do login, uma chave velha ainda por purgar não pode bloquear ninguém: o upsert recomeça a janela.
        sql("INSERT INTO login_rate_limits (rate_key, window_started_at, attempts, updated_at) VALUES "
                + "('" + sha256("it566-stale") + "', now() - interval '3 days', 99, now() - interval '3 days')");
        limiter.checkIdentity("it566-stale").block(WAIT);
        assertThat(count("SELECT attempts FROM login_rate_limits WHERE rate_key = '" + sha256("it566-stale") + "'")).isEqualTo(1);
        sql("DELETE FROM login_rate_limits WHERE rate_key = '" + sha256("it566-stale") + "'");
    }

    private static String sha256(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String query) {
        return database.sql(query).map((row, meta) -> row.get(0, Long.class)).one().block(WAIT);
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

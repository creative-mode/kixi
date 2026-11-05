package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * Pays the cold-start cost of the login before the first student does (kixi#566).
 *
 * <p>The first login after a start measured 0.9 to 2.9 s against ~0.12 s once warm: BCrypt's loop is not yet compiled,
 * the classes of the security and R2DBC paths are not loaded, and the query plans are not prepared. A restart is when a
 * whole class arrives together, so that is exactly when it hurts. This runs one hash, one check and the two login
 * queries against an account that does not exist. It writes nothing, never delays the start (it runs after the
 * application is ready, on its own thread) and a failure only makes it log and stop: it is an optimisation, not a
 * dependency.</p>
 */
@Component
public class LoginWarmup {

    private static final Logger log = LoggerFactory.getLogger(LoginWarmup.class);
    private static final String GHOST = "__kixi_warmup__";
    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(10);
    /** BCrypt's inner loop needs a few runs to be compiled; the first ones are the slow ones. */
    private static final int HASH_ROUNDS = 3;

    private final PasswordEncoder passwordEncoder;
    private final AccountRepository accounts;
    private final RoleRepository roles;
    private final boolean enabled;

    public LoginWarmup(PasswordEncoder passwordEncoder, AccountRepository accounts, RoleRepository roles,
                       @Value("${app.auth.warmup-enabled:true}") boolean enabled) {
        this.passwordEncoder = passwordEncoder;
        this.accounts = accounts;
        this.roles = roles;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        Schedulers.boundedElastic().schedule(this::warm);
    }

    void warm() {
        long started = System.nanoTime();
        try {
            for (int i = 0; i < HASH_ROUNDS; i++) {
                passwordEncoder.matches(GHOST, passwordEncoder.encode(GHOST));
            }
            accounts.findForLogin(GHOST, GHOST).block(STEP_TIMEOUT);
            roles.findActiveNamesByAccountId(-1L).collectList().block(STEP_TIMEOUT);
            log.info("Login warm-up done in {} ms", (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException error) {
            log.warn("Login warm-up skipped: type={}", error.getClass().getSimpleName());
        }
    }
}

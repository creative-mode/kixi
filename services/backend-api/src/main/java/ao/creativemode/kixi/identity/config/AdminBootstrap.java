package ao.creativemode.kixi.identity.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.AccountRole;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Creates the first ADMIN account on startup from
 * {@code APP_BOOTSTRAP_ADMIN_USERNAME} / {@code _EMAIL} / {@code _PASSWORD}.
 *
 * <p>Idempotent and conservative, by design:</p>
 * <ul>
 *   <li>does nothing when the variables are absent (logs a warning, boots normally);</li>
 *   <li>does nothing when an ADMIN already exists — it never modifies accounts;</li>
 *   <li>never uses a default password: without variables nothing is created;</li>
 *   <li>in the {@code prod} profile, weak or example passwords abort startup,
 *       like {@link ProdJwtSecretGuard} does for the JWT secret.</li>
 * </ul>
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int PROD_MIN_PASSWORD_LENGTH = 12;
    static final Set<String> WEAK_PASSWORDS = new HashSet<>(Arrays.asList(
            "password", "12345678", "123456789", "admin", "admin123",
            "kixi", "kixi123", "kixi1234", "changeme", "secret"));

    private final AdminBootstrapProperties properties;
    private final Environment environment;
    private final PasswordEncoder passwordEncoder;
    private final TransactionalOperator transactionalOperator;
    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final UserRepository userRepository;

    public AdminBootstrap(
            AdminBootstrapProperties properties,
            Environment environment,
            PasswordEncoder passwordEncoder,
            TransactionalOperator transactionalOperator,
            AccountRepository accountRepository,
            RoleRepository roleRepository,
            AccountRoleRepository accountRoleRepository,
            UserRepository userRepository) {
        this.properties = properties;
        this.environment = environment;
        this.passwordEncoder = passwordEncoder;
        this.transactionalOperator = transactionalOperator;
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.accountRoleRepository = accountRoleRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap().block();
    }

    Mono<Void> bootstrap() {
        if (!properties.isConfigured()) {
            log.warn("ADMIN bootstrap skipped: set APP_BOOTSTRAP_ADMIN_USERNAME, "
                    + "APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD to create "
                    + "the first administrator");
            return Mono.empty();
        }
        return roleRepository.findByNameAndDeletedAtIsNull("ADMIN")
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "ADMIN bootstrap failed: role ADMIN is not seeded")))
                .flatMap(role -> accountRoleRepository
                        .findByRoleIdAndDeletedAtIsNull(role.getId())
                        .hasElements()
                        .flatMap(adminExists -> {
                            if (adminExists) {
                                log.info("ADMIN bootstrap skipped: an administrator already exists");
                                return Mono.<Void>empty();
                            }
                            return createAdmin();
                        }));
    }

    private Mono<Void> createAdmin() {
        String username = properties.getUsername().trim();
        String email = properties.getEmail().trim().toLowerCase(Locale.ROOT);
        String password = properties.getPassword();
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        String problem = validatePassword(password, prod);
        if (problem != null) {
            if (prod) {
                throw new IllegalStateException(
                        "Refusing to start with 'prod' profile: bootstrap admin password " + problem);
            }
            log.error("ADMIN bootstrap skipped: password {}", problem);
            return Mono.empty();
        }

        return accountRepository.findByUsernameAndDeletedAtIsNull(username)
                .hasElement()
                .flatMap(usernameTaken -> {
                    if (usernameTaken) {
                        // Branch, don't swallow: an empty Mono completes, and anything
                        // chained after it would still run — including the insert.
                        log.error("ADMIN bootstrap skipped: username '{}' is already taken", username);
                        return Mono.<Void>empty();
                    }
                    return accountRepository.findByEmailAndDeletedAtIsNull(email)
                            .hasElement()
                            .flatMap(emailTaken -> {
                                if (emailTaken) {
                                    log.error("ADMIN bootstrap skipped: email '{}' is already taken", email);
                                    return Mono.<Void>empty();
                                }
                                return transactionalOperator.transactional(Mono.defer(() -> roleRepository
                                        .findByNameAndDeletedAtIsNull("ADMIN")
                                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                                "ADMIN bootstrap failed: role ADMIN is not seeded")))
                                        .flatMap(role -> {
                                            Account account = new Account();
                                            account.setUsername(username);
                                            account.setEmail(email);
                                            account.setPasswordHash(passwordEncoder.encode(password));
                                            account.setEmailVerified(false);
                                            account.setActive(true);
                                            account.setDeletedAt(null);

                                            // Account, role and profile go in one transaction:
                                            // a partial write would leave an account without
                                            // ADMIN behind, and the next boot would collide
                                            // with it instead of finishing the job.
                                            return accountRepository.save(account).flatMap(saved -> {
                                                User user = new User();
                                                user.setAccountId(saved.getId());
                                                user.setFirstName(firstNameOrDefault(username));
                                                user.setLastName(lastNameOrDefault());
                                                user.setDeletedAt(null);

                                                return Mono.when(
                                                                accountRoleRepository.save(
                                                                        new AccountRole(saved.getId(), role.getId())),
                                                                userRepository.save(user))
                                                        .then(Mono.fromRunnable(() -> log.info(
                                                                "ADMIN bootstrap: administrator '{}' created", username)));
                                            });
                                        })));
                            });
                });
    }

    private String firstNameOrDefault(String username) {
        return properties.getFirstName() != null && !properties.getFirstName().isBlank()
                ? properties.getFirstName().trim() : username;
    }

    private String lastNameOrDefault() {
        return properties.getLastName() != null && !properties.getLastName().isBlank()
                ? properties.getLastName().trim() : "Admin";
    }

    /**
     * Returns a description of the problem, or {@code null} when the password is fine.
     * Package-visible for tests.
     */
    static String validatePassword(String password, boolean prod) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "must contain at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        if (!prod) {
            return null;
        }
        if (password.length() < PROD_MIN_PASSWORD_LENGTH) {
            return "must contain at least " + PROD_MIN_PASSWORD_LENGTH + " characters in prod";
        }
        String normalized = password.trim().toLowerCase(Locale.ROOT);
        if (WEAK_PASSWORDS.contains(normalized)
                || ProdJwtSecretGuard.PLACEHOLDERS.contains(normalized)
                || ProdJwtSecretGuard.isPlaceholder(normalized)) {
            return "uses a weak or example value";
        }
        for (String weak : WEAK_PASSWORDS) {
            if (normalized.startsWith(weak) && isDigits(normalized.substring(weak.length()))) {
                return "uses a weak or example value";
            }
        }
        return null;
    }

    private static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }
}

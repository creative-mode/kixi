package ao.creativemode.kixi.identity.security;

import ao.creativemode.kixi.identity.config.JwtProperties;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Confirma, de {@code revalidationMs} em {@code revalidationMs} por conta, que o dono de um token ainda pode usá-lo (kixi#450).
 *
 * <p>O JWT dura 24 h e leva os papéis dentro. Sem isto, uma conta desactivada ou apagada, ou um papel retirado, continuava a valer até o token
 * expirar. Aqui lê-se a conta e os papéis na base, no máximo uma vez por janela e por conta (os pedidos em simultâneo partilham a leitura), e
 * o filtro usa o resultado: conta inactiva ou apagada não autentica, e só valem os papéis do token que a conta ainda tem. Um papel novo só
 * vale depois de novo login: quem o acrescentou não está a dar acesso a um token já emitido.
 *
 * <p>Se a leitura falhar (a base em baixo) o veredicto é {@link Kind#UNKNOWN} e o filtro confia no token como antes: uma falha da base já
 * deixa a API sem resposta, e não deve deixar também toda a gente à porta. A falha não fica guardada.
 */
@Component
public class AccountSessionGate {

    private static final Logger log = LoggerFactory.getLogger(AccountSessionGate.class);
    private static final int MAX_ENTRIES = 10_000;

    public enum Kind { VALID, REVOKED, UNKNOWN }

    public record Verdict(Kind kind, Set<String> roles) {
        static final Verdict REVOKED = new Verdict(Kind.REVOKED, Set.of());
        static final Verdict UNKNOWN = new Verdict(Kind.UNKNOWN, Set.of());

        static Verdict valid(Set<String> roles) {
            return new Verdict(Kind.VALID, roles);
        }
    }

    private record Entry(Mono<Verdict> verdict, long at) {
    }

    private final AccountRepository accounts;
    private final AccountRoleRepository accountRoles;
    private final RoleRepository roles;
    private final long revalidationMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    /** O construtor do Spring. Há outro (visível ao pacote) para os testes poderem fixar a janela e o relógio; sem esta marca o Spring não sabe qual usar. */
    @Autowired
    public AccountSessionGate(AccountRepository accounts, AccountRoleRepository accountRoles, RoleRepository roles, JwtProperties properties) {
        this(accounts, accountRoles, roles, properties.getRevalidationMs(), System::currentTimeMillis);
    }

    AccountSessionGate(AccountRepository accounts, AccountRoleRepository accountRoles, RoleRepository roles, long revalidationMs, LongSupplier clock) {
        this.accounts = accounts;
        this.accountRoles = accountRoles;
        this.roles = roles;
        this.revalidationMs = revalidationMs;
        this.clock = clock;
    }

    /** O nome de um papel sem o prefixo {@code ROLE_} e em maiúsculas, para comparar o do token com o da base. */
    public static String normalize(String role) {
        String upper = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("ROLE_") ? upper.substring(5) : upper;
    }

    public Mono<Verdict> verdict(Long accountId) {
        if (revalidationMs <= 0) {
            return Mono.just(Verdict.UNKNOWN); // desligado: o filtro confia no token
        }
        long now = clock.getAsLong();
        Entry existing = cache.get(accountId);
        if (existing != null && now - existing.at() < revalidationMs) {
            return existing.verdict();
        }
        if (cache.size() >= MAX_ENTRIES) {
            cache.values().removeIf(entry -> now - entry.at() >= revalidationMs);
        }
        Mono<Verdict> loaded = load(accountId)
                .onErrorResume(error -> {
                    log.warn("Could not revalidate an account session; trusting the token this time: type={}", error.getClass().getSimpleName());
                    cache.remove(accountId);
                    return Mono.just(Verdict.UNKNOWN);
                })
                .cache();
        cache.put(accountId, new Entry(loaded, now));
        return loaded;
    }

    private Mono<Verdict> load(Long accountId) {
        return accounts.findByIdAndDeletedAtIsNull(accountId)
                .filter(account -> Boolean.TRUE.equals(account.getActive()))
                .flatMap(account -> accountRoles.findByAccountIdAndDeletedAtIsNull(accountId)
                        .flatMap(link -> roles.findByIdAndDeletedAtIsNull(link.getRoleId()))
                        .map(Role::getName)
                        .map(AccountSessionGate::normalize)
                        .collect(Collectors.toSet())
                        .map(Verdict::valid))
                .defaultIfEmpty(Verdict.REVOKED);
    }
}

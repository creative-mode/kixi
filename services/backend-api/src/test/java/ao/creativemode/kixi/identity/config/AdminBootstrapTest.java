package ao.creativemode.kixi.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.AccountRole;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AdminBootstrapTest {

    private AdminBootstrapProperties properties;
    private Environment environment;
    private PasswordEncoder passwordEncoder;
    private AccountRepository accountRepository;
    private RoleRepository roleRepository;
    private AccountRoleRepository accountRoleRepository;
    private UserRepository userRepository;
    private AdminBootstrap bootstrap;

    private final Role adminRole = role(7L);

    private static Role role(Long id) {
        Role role = new Role();
        role.setId(id);
        role.setName("ADMIN");
        return role;
    }

    @BeforeEach
    void setUp() {
        properties = new AdminBootstrapProperties();
        properties.setUsername("admin");
        properties.setEmail("admin@kixi.ao");
        properties.setPassword("a-strong-bootstrap-password");
        environment = mock(Environment.class);
        passwordEncoder = mock(PasswordEncoder.class);
        accountRepository = mock(AccountRepository.class);
        roleRepository = mock(RoleRepository.class);
        accountRoleRepository = mock(AccountRoleRepository.class);
        userRepository = mock(UserRepository.class);
        bootstrap = new AdminBootstrap(properties, environment, passwordEncoder,
                accountRepository, roleRepository, accountRoleRepository, userRepository);
        when(environment.getActiveProfiles()).thenReturn(new String[] { "docker" });
        when(passwordEncoder.encode(any())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));
    }

    @Test
    void doesNothingWhenVariablesAreMissing() {
        properties.setPassword("   ");

        StepVerifier.create(bootstrap.bootstrap()).verifyComplete();

        verify(roleRepository, never()).findByNameAndDeletedAtIsNull(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        when(roleRepository.findByNameAndDeletedAtIsNull("ADMIN")).thenReturn(Mono.just(adminRole));
        when(accountRoleRepository.findByRoleIdAndDeletedAtIsNull(7L))
                .thenReturn(Flux.just(new AccountRole(3L, 7L)));

        StepVerifier.create(bootstrap.bootstrap()).verifyComplete();

        verify(accountRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createsAdminWithRoleAndProfileWhenNoneExists() {
        when(roleRepository.findByNameAndDeletedAtIsNull("ADMIN")).thenReturn(Mono.just(adminRole));
        when(accountRoleRepository.findByRoleIdAndDeletedAtIsNull(7L)).thenReturn(Flux.empty());
        when(accountRepository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("admin@kixi.ao")).thenReturn(Mono.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account saved = inv.getArgument(0);
            saved.setId(42L);
            return Mono.just(saved);
        });
        when(accountRoleRepository.save(any(AccountRole.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(bootstrap.bootstrap()).verifyComplete();

        verify(accountRepository).save(any(Account.class));
        verify(accountRoleRepository).save(any(AccountRole.class));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void refusesWeakPasswordInProd() {
        when(environment.getActiveProfiles()).thenReturn(new String[] { "prod" });
        properties.setPassword("admin12345678");
        when(roleRepository.findByNameAndDeletedAtIsNull("ADMIN")).thenReturn(Mono.just(adminRole));
        when(accountRoleRepository.findByRoleIdAndDeletedAtIsNull(7L)).thenReturn(Flux.empty());

        assertThatThrownBy(() -> bootstrap.bootstrap().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void validatePasswordRules() {
        assertThat(AdminBootstrap.validatePassword("short", false))
                .contains("8 characters");
        assertThat(AdminBootstrap.validatePassword("long-enough-here", false)).isNull();
        assertThat(AdminBootstrap.validatePassword("ten-chars!", true))
                .contains("12 characters");
        assertThat(AdminBootstrap.validatePassword("admin12345678", true))
                .contains("weak or example");
        assertThat(AdminBootstrap.validatePassword("a-strong-bootstrap-password", true)).isNull();
    }
}

package ao.creativemode.kixi.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.dto.auth.RegisterRequest;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.AccountRole;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.identity.service.GoogleOAuth2Client.GoogleUserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthServiceTest {

    private AccountRepository accountRepository;
    private AccountRoleRepository accountRoleRepository;
    private RoleRepository roleRepository;
    private UserRepository userRepository;
    private JwtService jwtService;
    private PasswordEncoder passwordEncoder;
    private GoogleOAuth2Client googleOAuth2Client;
    private AuthService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        accountRoleRepository = mock(AccountRoleRepository.class);
        roleRepository = mock(RoleRepository.class);
        userRepository = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        googleOAuth2Client = mock(GoogleOAuth2Client.class);
        service = new AuthService(accountRepository, accountRoleRepository, roleRepository,
                userRepository, jwtService, passwordEncoder, googleOAuth2Client);

        when(jwtService.generateToken(anyLong(), anyList())).thenReturn("jwt-token");
        when(jwtService.getExpirationMs()).thenReturn(86_400_000L);
    }

    @Test
    void loginRejectsUnknownUsernameOrEmail() {
        when(accountRepository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(service.login("ghost", "whatever"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage()).isEqualTo("Invalid username or password");
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    void loginRejectsInactiveAccount() {
        Account inactive = account(1L, "admin", "hashed");
        inactive.setActive(false);
        when(accountRepository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Mono.just(inactive));
        stubUnusedEmailFallback("admin");

        StepVerifier.create(service.login("admin", "whatever"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage()).isEqualTo("Account is inactive");
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(401);
                })
                .verify();

        verify(accountRepository, never()).save(any());
    }

    @Test
    void loginRejectsWrongPassword() {
        Account existing = account(1L, "admin", "hashed");
        when(accountRepository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Mono.just(existing));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        stubUnusedEmailFallback("admin");

        StepVerifier.create(service.login("admin", "wrong"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage()).isEqualTo("Invalid username or password");
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    void loginReturnsTokenAndRolesOnSuccess() {
        Account existing = account(1L, "admin", "hashed");
        when(accountRepository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Mono.just(existing));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(accountRepository.save(existing)).thenReturn(Mono.just(existing));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L))
                .thenReturn(Flux.just(new AccountRole(1L, 2L)));
        when(roleRepository.findById(2L)).thenReturn(Mono.just(role(2L, "ADMIN")));
        stubUnusedEmailFallback("admin");

        StepVerifier.create(service.login("admin", "correct"))
                .assertNext(response -> {
                    assertThat(response.accessToken()).isEqualTo("jwt-token");
                    assertThat(response.accountId()).isEqualTo(1L);
                    assertThat(response.roles()).containsExactly("ADMIN");
                })
                .verifyComplete();

        assertThat(existing.getLastLogin()).isNotNull();
    }

    @Test
    void loginChecksThePasswordOffTheThreadThatDeliveredTheAccount() {
        Account existing = account(1L, "admin", "hashed");
        when(accountRepository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Mono.just(existing));
        java.util.concurrent.atomic.AtomicReference<String> bcryptThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(passwordEncoder.matches("correct", "hashed")).thenAnswer(invocation -> {
            bcryptThread.set(Thread.currentThread().getName());
            return true;
        });
        when(accountRepository.save(existing)).thenReturn(Mono.just(existing));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L))
                .thenReturn(Flux.just(new AccountRole(1L, 2L)));
        when(roleRepository.findById(2L)).thenReturn(Mono.just(role(2L, "ADMIN")));
        stubUnusedEmailFallback("admin");

        StepVerifier.create(service.login("admin", "correct")).expectNextCount(1).verifyComplete();

        // In production the account arrives on an R2DBC I/O thread shared by every
        // request; BCrypt must not run there (#124).
        assertThat(bcryptThread.get()).startsWith("boundedElastic");
    }

    @Test
    void registerHashesThePasswordOffTheCallingThread() {
        when(accountRepository.findByUsernameAndDeletedAtIsNull("newuser")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("new@kixi.ao")).thenReturn(Mono.empty());
        when(roleRepository.findByNameAndDeletedAtIsNull("STUDENT")).thenReturn(Mono.just(role(3L, "STUDENT")));
        java.util.concurrent.atomic.AtomicReference<String> bcryptThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(passwordEncoder.encode("password123")).thenAnswer(invocation -> {
            bcryptThread.set(Thread.currentThread().getName());
            return "hashed-password";
        });
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(9L);
            return Mono.just(saved);
        });
        when(accountRoleRepository.save(any(AccountRole.class))).thenReturn(Mono.just(new AccountRole(9L, 3L)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.register(
                        new RegisterRequest("newuser", "new@kixi.ao", "password123", "Nova", "Conta")))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(bcryptThread.get()).startsWith("boundedElastic");
    }

    @Test
    void loginFallsBackToEmailLookupWhenUsernameNotFound() {
        Account existing = account(1L, "admin", "hashed");
        when(accountRepository.findByUsernameAndDeletedAtIsNull("admin@kixi.ao")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("admin@kixi.ao")).thenReturn(Mono.just(existing));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(accountRepository.save(existing)).thenReturn(Mono.just(existing));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L)).thenReturn(Flux.empty());

        StepVerifier.create(service.login("admin@kixi.ao", "correct"))
                .assertNext(response -> assertThat(response.roles()).isEmpty())
                .verifyComplete();
    }

    @Test
    void loginNormalizesEmailBeforeLookup() {
        Account existing = account(1L, "student", "hashed");
        when(accountRepository.findByUsernameAndDeletedAtIsNull("Student@Kixi.AO"))
                .thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("student@kixi.ao"))
                .thenReturn(Mono.just(existing));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(accountRepository.save(existing)).thenReturn(Mono.just(existing));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L)).thenReturn(Flux.empty());

        StepVerifier.create(service.login("Student@Kixi.AO", "correct"))
                .assertNext(response -> assertThat(response.accountId()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void loginWithGoogleReusesExistingAccountByEmail() {
        Account existing = account(1L, "existing", "hashed");
        existing.setEmailVerified(true);
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "existing@kixi.ao", true,
                        "Existing User", null)));
        when(accountRepository.findByGoogleSubAndDeletedAtIsNull("google-sub")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("existing@kixi.ao")).thenReturn(Mono.just(existing));
        when(accountRepository.save(existing)).thenReturn(Mono.just(existing));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L)).thenReturn(Flux.empty());
        // findOrCreateAccountFromGoogle() builds its .switchIfEmpty(createAccountAndUserFromGoogle(...))
        // argument eagerly, which calls roleRepository.findByNameAndDeletedAtIsNull(...) immediately,
        // even though this branch is never subscribed once the email lookup above succeeds.
        when(roleRepository.findByNameAndDeletedAtIsNull("STUDENT")).thenReturn(Mono.empty());

        StepVerifier.create(service.loginWithGoogle("code"))
                .assertNext(response -> assertThat(response.accountId()).isEqualTo(1L))
                .verifyComplete();

        assertThat(existing.getGoogleSub()).isEqualTo("google-sub");
        verify(accountRepository, never()).save(org.mockito.ArgumentMatchers.argThat(a -> !a.getId().equals(1L)));
    }

    @Test
    void loginWithGoogleRejectsMissingEmailFromProvider() {
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo(null, "No Email", null)));

        StepVerifier.create(service.loginWithGoogle("code"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage()).isEqualTo("Google did not provide email");
                })
                .verify();
    }

    @Test
    void loginWithGoogleRejectsWhenDefaultRoleIsNotConfigured() {
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "new@kixi.ao", true,
                        "New User", null)));
        when(accountRepository.findByGoogleSubAndDeletedAtIsNull("google-sub")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("new@kixi.ao")).thenReturn(Mono.empty());
        when(accountRepository.findByUsernameAndDeletedAtIsNull("new@kixi.ao")).thenReturn(Mono.empty());
        when(accountRepository.findByUsernameAndDeletedAtIsNull("new")).thenReturn(Mono.empty());
        when(roleRepository.findByNameAndDeletedAtIsNull("STUDENT")).thenReturn(Mono.empty());

        StepVerifier.create(service.loginWithGoogle("code"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("Default role is not configured: STUDENT");
                })
                .verify();

        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void loginWithGoogleCreatesAccountRoleAndUserForNewEmail() {
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "New.User@Kixi.Ao", true,
                        "New User", "pic.png")));
        when(accountRepository.findByGoogleSubAndDeletedAtIsNull("google-sub")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("new.user@kixi.ao")).thenReturn(Mono.empty());
        when(accountRepository.findByUsernameAndDeletedAtIsNull("new.user")).thenReturn(Mono.empty());
        when(passwordEncoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("hashed-random");
        when(roleRepository.findByNameAndDeletedAtIsNull("STUDENT")).thenReturn(Mono.just(role(3L, "STUDENT")));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(9L);
            return Mono.just(saved);
        });
        when(accountRoleRepository.save(any(AccountRole.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Flux.empty());

        StepVerifier.create(service.loginWithGoogle("code"))
                .assertNext(response -> assertThat(response.accountId()).isEqualTo(9L))
                .verifyComplete();

        verify(accountRepository, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.argThat(
                a -> "new.user".equals(a.getUsername())
                        && "google-sub".equals(a.getGoogleSub())
                        && Boolean.TRUE.equals(a.getEmailVerified())));
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(
                u -> u.getFirstName().equals("New") && u.getLastName().equals("User")));
    }

    @Test
    void googleCannotCreateOrLinkAnUnverifiedEmail() {
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "new@kixi.ao", false,
                        "New User", null)));

        StepVerifier.create(service.loginWithGoogle("code"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(401))
                .verify();
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void googleCannotAdoptAnUnverifiedLocalAccountByEmail() {
        Account local = account(1L, "existing", "hashed");
        local.setEmail("existing@kixi.ao");
        local.setEmailVerified(false);
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "existing@kixi.ao", true,
                        "Existing User", null)));
        when(accountRepository.findByGoogleSubAndDeletedAtIsNull("google-sub")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("existing@kixi.ao")).thenReturn(Mono.just(local));

        StepVerifier.create(service.loginWithGoogle("code"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(401))
                .verify();
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void googleReloginUsesThePersistedSubjectInsteadOfMatchingEmail() {
        Account linked = account(1L, "existing", "hashed");
        linked.setGoogleSub("google-sub");
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "changed@kixi.ao", true,
                        "Existing User", null)));
        when(accountRepository.findByGoogleSubAndDeletedAtIsNull("google-sub")).thenReturn(Mono.just(linked));
        when(accountRepository.save(linked)).thenReturn(Mono.just(linked));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L)).thenReturn(Flux.empty());

        StepVerifier.create(service.loginWithGoogle("code"))
                .assertNext(response -> assertThat(response.accountId()).isEqualTo(1L))
                .verifyComplete();

        verify(accountRepository, never()).findByEmailAndDeletedAtIsNull("changed@kixi.ao");
    }

    @Test
    void explicitGoogleLinkRejectsWrongLocalPasswordBeforeUsingGoogleCode() {
        Account local = account(1L, "existing", "hashed");
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(local));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        StepVerifier.create(service.verifyPasswordForGoogleLink(1L, "wrong"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(401))
                .verify();

        verify(googleOAuth2Client, never()).exchangeCodeForAccessToken(any());
    }

    @Test
    void authenticatedExplicitLinkRequiresPasswordAndMatchingVerifiedGoogleIdentity() {
        Account local = account(1L, "existing", "hashed");
        local.setEmail("existing@kixi.ao");
        local.setEmailVerified(false);
        when(accountRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(local));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "existing@kixi.ao", true,
                        "Existing User", null)));
        when(accountRepository.findByGoogleSubAndDeletedAtIsNull("google-sub")).thenReturn(Mono.empty());
        when(accountRepository.save(local)).thenReturn(Mono.just(local));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(1L)).thenReturn(Flux.empty());

        StepVerifier.create(service.verifyPasswordForGoogleLink(1L, "correct"))
                .verifyComplete();
        StepVerifier.create(service.completeGoogleLink(1L, "code"))
                .assertNext(response -> assertThat(response.accountId()).isEqualTo(1L))
                .verifyComplete();

        assertThat(local.getGoogleSub()).isEqualTo("google-sub");
        assertThat(local.getEmailVerified()).isTrue();
    }

    @Test
    void googleUsernameCollisionGetsAnUnambiguousGeneratedUsername() {
        when(googleOAuth2Client.exchangeCodeForAccessToken("code")).thenReturn(Mono.just("access-token"));
        when(googleOAuth2Client.getUserInfo("access-token"))
                .thenReturn(Mono.just(new GoogleUserInfo("google-sub", "same@kixi.ao", true,
                        "New User", null)));
        when(accountRepository.findByGoogleSubAndDeletedAtIsNull("google-sub")).thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("same@kixi.ao")).thenReturn(Mono.empty());
        when(accountRepository.findByUsernameAndDeletedAtIsNull("same")).thenReturn(Mono.just(account(8L, "same", "hash")));
        when(roleRepository.findByNameAndDeletedAtIsNull("STUDENT")).thenReturn(Mono.just(role(3L, "STUDENT")));
        when(passwordEncoder.encode(any())).thenReturn("hashed-random");
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(9L);
            return Mono.just(saved);
        });
        when(accountRoleRepository.save(any(AccountRole.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(accountRoleRepository.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Flux.empty());

        StepVerifier.create(service.loginWithGoogle("code"))
                .assertNext(response -> assertThat(response.accountId()).isEqualTo(9L))
                .verifyComplete();

        verify(accountRepository, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.argThat(
                account -> account.getUsername().startsWith("same-") && account.getUsername().length() <= 100));
    }

    @Test
    void registerCreatesAccountUserAndStudentRoleAndReturnsToken() {
        when(accountRepository.findByUsernameAndDeletedAtIsNull("new-student"))
                .thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("student@kixi.ao"))
                .thenReturn(Mono.empty());
        when(roleRepository.findByNameAndDeletedAtIsNull("STUDENT"))
                .thenReturn(Mono.just(role(3L, "STUDENT")));
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(9L);
            return Mono.just(saved);
        });
        when(accountRoleRepository.save(any(AccountRole.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        RegisterRequest request = new RegisterRequest(
                " new-student ", " Student@Kixi.AO ", "password123", " Ana ", " Silva ");

        StepVerifier.create(service.register(request))
                .assertNext(response -> {
                    assertThat(response.accountId()).isEqualTo(9L);
                    assertThat(response.accessToken()).isEqualTo("jwt-token");
                    assertThat(response.roles()).containsExactly("STUDENT");
                })
                .verifyComplete();

        verify(passwordEncoder).encode("password123");
        verify(accountRoleRepository).save(org.mockito.ArgumentMatchers.argThat(
                accountRole -> accountRole.getAccountId().equals(9L)
                        && accountRole.getRoleId().equals(3L)));
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(
                user -> user.getAccountId().equals(9L)
                        && user.getFirstName().equals("Ana")
                        && user.getLastName().equals("Silva")));
    }

    @Test
    void registerRejectsDuplicateUsernameBeforeCreatingAccount() {
        when(accountRepository.findByUsernameAndDeletedAtIsNull("existing"))
                .thenReturn(Mono.just(account(1L, "existing", "hashed")));

        StepVerifier.create(service.register(validRegisterRequest("existing", "new@kixi.ao")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(error).hasMessage("Username or email already in use");
                })
                .verify();

        verify(accountRepository, never()).save(any(Account.class));
        verify(roleRepository, never()).findByNameAndDeletedAtIsNull("STUDENT");
    }

    @Test
    void registerRejectsDuplicateEmailBeforeCreatingAccount() {
        when(accountRepository.findByUsernameAndDeletedAtIsNull("new-student"))
                .thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("existing@kixi.ao"))
                .thenReturn(Mono.just(account(1L, "existing", "hashed")));

        StepVerifier.create(service.register(validRegisterRequest("new-student", "Existing@Kixi.AO")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(error).hasMessage("Username or email already in use");
                })
                .verify();

        verify(accountRepository, never()).save(any(Account.class));
        verify(roleRepository, never()).findByNameAndDeletedAtIsNull("STUDENT");
    }

    @Test
    void registerRejectsWhenStudentRoleIsNotConfigured() {
        when(accountRepository.findByUsernameAndDeletedAtIsNull("new-student"))
                .thenReturn(Mono.empty());
        when(accountRepository.findByEmailAndDeletedAtIsNull("new@kixi.ao"))
                .thenReturn(Mono.empty());
        when(roleRepository.findByNameAndDeletedAtIsNull("STUDENT"))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.register(validRegisterRequest("new-student", "new@kixi.ao")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(500);
                    assertThat(error).hasMessage("Default role is not configured: STUDENT");
                })
                .verify();

        verify(accountRepository, never()).save(any(Account.class));
    }

    /** The email fallback is lazy, so it is only queried after a username miss. */
    private void stubUnusedEmailFallback(String usernameOrEmail) {
        when(accountRepository.findByEmailAndDeletedAtIsNull(usernameOrEmail)).thenReturn(Mono.empty());
    }

    private Account account(Long id, String username, String passwordHash) {
        Account account = new Account();
        account.setId(id);
        account.setUsername(username);
        account.setEmail(username + "@kixi.ao");
        account.setPasswordHash(passwordHash);
        account.setActive(true);
        return account;
    }

    private Role role(Long id, String name) {
        Role role = new Role(name, null);
        role.setId(id);
        return role;
    }

    private RegisterRequest validRegisterRequest(String username, String email) {
        return new RegisterRequest(username, email, "password123", "Ana", "Silva");
    }
}

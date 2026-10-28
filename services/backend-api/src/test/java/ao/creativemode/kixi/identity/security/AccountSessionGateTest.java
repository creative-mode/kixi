package ao.creativemode.kixi.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.AccountRole;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.security.AccountSessionGate.Kind;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** O gate confirma na base, no máximo uma vez por janela, que a conta de um token ainda pode usá-lo (kixi#450). */
class AccountSessionGateTest {

    private static final long JANELA = 30_000;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountRoleRepository accountRoles = mock(AccountRoleRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final AtomicLong agora = new AtomicLong(1_000_000);
    private AccountSessionGate gate;

    @BeforeEach
    void setUp() {
        gate = new AccountSessionGate(accounts, accountRoles, roles, JANELA, agora::get);
    }

    private static Account conta(boolean activa) {
        Account a = new Account();
        a.setId(7L);
        a.setActive(activa);
        return a;
    }

    private void contaComPapel(boolean activa, String papel) {
        when(accounts.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(conta(activa)));
        when(accountRoles.findByAccountIdAndDeletedAtIsNull(7L)).thenReturn(Flux.just(new AccountRole(7L, 3L)));
        Role role = new Role();
        role.setName(papel);
        when(roles.findByIdAndDeletedAtIsNull(3L)).thenReturn(Mono.just(role));
    }

    @Test
    void umaContaActivaDaOsSeusPapeisSemOPrefixoNemAMaiuscula() {
        contaComPapel(true, "ROLE_student");

        StepVerifier.create(gate.verdict(7L))
                .assertNext(v -> {
                    assertThat(v.kind()).isEqualTo(Kind.VALID);
                    assertThat(v.roles()).containsExactly("STUDENT");
                })
                .verifyComplete();
    }

    @Test
    void umaContaDesactivadaEstaRevogada() {
        when(accounts.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(conta(false)));

        StepVerifier.create(gate.verdict(7L)).assertNext(v -> assertThat(v.kind()).isEqualTo(Kind.REVOKED)).verifyComplete();
    }

    @Test
    void umaContaApagadaOuInexistenteEstaRevogada() {
        when(accounts.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.empty());

        StepVerifier.create(gate.verdict(7L)).assertNext(v -> assertThat(v.kind()).isEqualTo(Kind.REVOKED)).verifyComplete();
    }

    @Test
    void dentroDaJanelaNaoVaiOutraVezABase() {
        contaComPapel(true, "STUDENT");

        gate.verdict(7L).block();
        agora.addAndGet(JANELA - 1);
        gate.verdict(7L).block();

        verify(accounts, times(1)).findByIdAndDeletedAtIsNull(7L);
    }

    @Test
    void passadaAJanelaVoltaALerEUmaContaDesactivadaNoIntervaloPassaARevogada() {
        contaComPapel(true, "STUDENT");
        assertThat(gate.verdict(7L).block().kind()).isEqualTo(Kind.VALID);

        // o administrador desactiva a conta; antes de a janela acabar o veredicto ainda é o antigo
        when(accounts.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(conta(false)));
        agora.addAndGet(JANELA - 1);
        assertThat(gate.verdict(7L).block().kind()).isEqualTo(Kind.VALID);

        // depois dela, a revogação vale: é o que reduz as 24 h do token a no máximo 30 s
        agora.addAndGet(1);
        assertThat(gate.verdict(7L).block().kind()).isEqualTo(Kind.REVOKED);
    }

    @Test
    void pedidosEmSimultaneoPartilhamAMesmaLeitura() {
        contaComPapel(true, "STUDENT");

        Mono.zip(gate.verdict(7L), gate.verdict(7L), gate.verdict(7L)).block();

        verify(accounts, times(1)).findByIdAndDeletedAtIsNull(7L);
    }

    @Test
    void umaFalhaDaBaseNaoRevogaNemFicaGuardada() {
        when(accounts.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.error(new IllegalStateException("base em baixo")));

        assertThat(gate.verdict(7L).block().kind()).isEqualTo(Kind.UNKNOWN);

        // a base volta: o pedido seguinte já lê de novo, sem esperar pela janela
        contaComPapel(true, "STUDENT");
        assertThat(gate.verdict(7L).block().kind()).isEqualTo(Kind.VALID);
    }

    @Test
    void comAJanelaAZeroDesligaAVerificacao() {
        AccountSessionGate desligado = new AccountSessionGate(accounts, accountRoles, roles, 0, agora::get);

        assertThat(desligado.verdict(7L).block().kind()).isEqualTo(Kind.UNKNOWN);
        verify(accounts, times(0)).findByIdAndDeletedAtIsNull(7L);
    }

    @Test
    void normalizarTiraOPrefixoEAMaiuscula() {
        assertThat(AccountSessionGate.normalize("ROLE_Admin")).isEqualTo("ADMIN");
        assertThat(AccountSessionGate.normalize(" teacher ")).isEqualTo("TEACHER");
        assertThat(AccountSessionGate.normalize(null)).isEmpty();
    }
}

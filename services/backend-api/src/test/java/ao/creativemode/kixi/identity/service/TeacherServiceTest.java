package ao.creativemode.kixi.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.dto.accounts.AccountRequest;
import ao.creativemode.kixi.identity.dto.accounts.AccountResponse;
import ao.creativemode.kixi.identity.dto.teachers.TeacherRequest;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TeacherServiceTest {

    private TeacherRepository repository;
    private RoleRepository roleRepository;
    private AccountService accountService;
    private AccountRoleService accountRoleService;
    private TeacherService service;

    @BeforeEach
    void setUp() {
        repository = mock(TeacherRepository.class);
        roleRepository = mock(RoleRepository.class);
        accountService = mock(AccountService.class);
        accountRoleService = mock(AccountRoleService.class);
        service = new TeacherService(repository, roleRepository, accountService, accountRoleService);
    }

    @Test
    void grantAccessCreatesAccountAssignsTeacherRoleAndLinksIt() {
        AccountRequest request = new AccountRequest("ana.silva", "ana@itel.ao", "password123");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(teacher(1L, null)));
        when(roleRepository.findByNameAndDeletedAtIsNull("TEACHER")).thenReturn(Mono.just(role(7L, "TEACHER")));
        when(accountService.create(request)).thenReturn(Mono.just(accountResponse(50L)));
        when(accountRoleService.assignRoleToAccount(50L, 7L)).thenReturn(Mono.empty());
        when(repository.save(any(Teacher.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.grantAccess(1L, request))
                .assertNext(response -> {
                    assertThat(response.accountId()).isEqualTo(50L);
                    assertThat(response.hasAccess()).isTrue();
                })
                .verifyComplete();

        verify(accountRoleService).assignRoleToAccount(50L, 7L);
    }

    @Test
    void grantAccessFailsWhenTeacherAlreadyHasAccess() {
        AccountRequest request = new AccountRequest("ana.silva", "ana@itel.ao", "password123");
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(teacher(1L, 9L)));

        StepVerifier.create(service.grantAccess(1L, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verifyNoInteractions(accountService);
    }

    @Test
    void revokeAccessTrashesTheAccountAndUnlinksIt() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(teacher(1L, 9L)));
        when(accountService.softDelete(9L)).thenReturn(Mono.empty());
        when(repository.save(any(Teacher.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.revokeAccess(1L))
                .assertNext(response -> {
                    assertThat(response.accountId()).isNull();
                    assertThat(response.hasAccess()).isFalse();
                })
                .verifyComplete();

        verify(accountService).softDelete(9L);
    }

    @Test
    void revokeAccessFailsWhenTeacherHasNoAccess() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(teacher(1L, null)));

        StepVerifier.create(service.revokeAccess(1L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verifyNoInteractions(accountService);
    }

    @Test
    void createNormalizesEmailAndBlankFields() {
        when(repository.save(any(Teacher.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        TeacherRequest request = new TeacherRequest(" Ana ", " Silva ", " Ana@Itel.AO ", "  ", null, "T-1");

        StepVerifier.create(service.create(request))
                .assertNext(response -> assertThat(response.hasAccess()).isFalse())
                .verifyComplete();

        ArgumentCaptor<Teacher> saved = ArgumentCaptor.forClass(Teacher.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getFirstName()).isEqualTo("Ana");
        assertThat(saved.getValue().getLastName()).isEqualTo("Silva");
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@itel.ao");
        assertThat(saved.getValue().getPhoto()).isNull();
        assertThat(saved.getValue().getEmployeeNumber()).isEqualTo("T-1");
    }

    @Test
    void createMapsDuplicateEmployeeNumberToConflict() {
        when(repository.save(any(Teacher.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate")));

        TeacherRequest request = new TeacherRequest("Ana", "Silva", null, null, null, "T-1");

        StepVerifier.create(service.create(request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    private Teacher teacher(Long id, Long accountId) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setAccountId(accountId);
        teacher.setFirstName("Ana");
        teacher.setLastName("Silva");
        return teacher;
    }

    private Role role(Long id, String name) {
        Role role = new Role();
        role.setId(id);
        role.setName(name);
        return role;
    }

    private AccountResponse accountResponse(Long id) {
        return new AccountResponse(id, "ana.silva", "ana@itel.ao", false, true, false,
                null, null, null, null);
    }
}

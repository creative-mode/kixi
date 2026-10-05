package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.dto.accounts.AccountRequest;
import ao.creativemode.kixi.identity.dto.teachers.TeacherRequest;
import ao.creativemode.kixi.identity.dto.teachers.TeacherResponse;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Teacher CRUD plus platform access: a teacher can exist as a record only, and
 * is given access by linking an account that holds the TEACHER role.
 */
@Service
public class TeacherService {

    static final String TEACHER_ROLE = "TEACHER";

    private final TeacherRepository repository;
    private final RoleRepository roleRepository;
    private final AccountService accountService;
    private final AccountRoleService accountRoleService;

    public TeacherService(
        TeacherRepository repository,
        RoleRepository roleRepository,
        AccountService accountService,
        AccountRoleService accountRoleService
    ) {
        this.repository = repository;
        this.roleRepository = roleRepository;
        this.accountService = accountService;
        this.accountRoleService = accountRoleService;
    }

    public Flux<TeacherResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull().map(this::toResponse);
    }

    public Flux<TeacherResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull().map(this::toResponse);
    }

    public Mono<TeacherResponse> findByIdActive(Long id) {
        return findActive(id).map(this::toResponse);
    }

    public Mono<TeacherResponse> create(TeacherRequest data) {
        Teacher teacher = new Teacher();
        apply(teacher, data);
        teacher.setDeletedAt(null);

        return repository
            .save(teacher)
            .map(this::toResponse)
            .onErrorMap(DataIntegrityViolationException.class, e ->
                ApiException.conflict("Employee number is already in use")
            );
    }

    public Mono<TeacherResponse> update(Long id, TeacherRequest data) {
        return findActive(id)
            .flatMap(teacher -> {
                apply(teacher, data);
                return repository
                    .save(teacher)
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                        ApiException.conflict("Employee number is already in use")
                    );
            })
            .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return findActive(id)
            .flatMap(teacher -> {
                teacher.markAsDeleted();
                return repository.save(teacher);
            })
            .then();
    }

    public Mono<Void> restore(Long id) {
        return repository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Teacher not found")))
            .flatMap(teacher -> {
                teacher.restore();
                return repository.save(teacher);
            })
            .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Only deleted teacher can be permanently removed"))
            )
            .flatMap(teacher ->
                repository
                    .delete(teacher)
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                        ApiException.conflict("Teacher is still linked to institutions")
                    )
            )
            .then();
    }

    /**
     * Gives the teacher access to the platform: creates the account and
     * assigns it the TEACHER role, then links it to the teacher.
     */
    @Transactional
    public Mono<TeacherResponse> grantAccess(Long id, AccountRequest account) {
        return findActive(id)
            .flatMap(teacher -> {
                if (teacher.hasAccess()) {
                    return Mono.<Teacher>error(ApiException.conflict("Teacher already has platform access"));
                }
                return roleRepository
                    .findByNameAndDeletedAtIsNull(TEACHER_ROLE)
                    .switchIfEmpty(Mono.error(ApiException.conflict("Role is not configured: " + TEACHER_ROLE)))
                    .flatMap(role ->
                        accountService
                            .create(account)
                            .flatMap(created ->
                                accountRoleService
                                    .assignRoleToAccount(created.id(), role.getId())
                                    .then(Mono.defer(() -> {
                                        teacher.setAccountId(created.id());
                                        return repository.save(teacher);
                                    }))
                            )
                    );
            })
            .map(this::toResponse);
    }

    /** Takes platform access away: the account is moved to the trash and unlinked. */
    @Transactional
    public Mono<TeacherResponse> revokeAccess(Long id) {
        return findActive(id)
            .flatMap(teacher -> {
                if (!teacher.hasAccess()) {
                    return Mono.<Teacher>error(ApiException.conflict("Teacher has no platform access"));
                }
                Long accountId = teacher.getAccountId();
                return accountService
                    .softDelete(accountId)
                    .then(Mono.defer(() -> {
                        teacher.setAccountId(null);
                        return repository.save(teacher);
                    }));
            })
            .map(this::toResponse);
    }

    private Mono<Teacher> findActive(Long id) {
        return repository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Teacher not found")));
    }

    private void apply(Teacher teacher, TeacherRequest data) {
        teacher.setFirstName(data.firstName().trim());
        teacher.setLastName(data.lastName().trim());
        teacher.setEmail(blankToNull(data.email()) == null ? null : data.email().trim().toLowerCase());
        teacher.setPhoto(blankToNull(data.photo()));
        teacher.setSpecialty(blankToNull(data.specialty()));
        teacher.setEmployeeNumber(blankToNull(data.employeeNumber()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TeacherResponse toResponse(Teacher entity) {
        return new TeacherResponse(
            entity.getId(),
            entity.getAccountId(),
            entity.hasAccess(),
            entity.getFirstName(),
            entity.getLastName(),
            entity.getEmail(),
            entity.getPhoto(),
            entity.getSpecialty(),
            entity.getEmployeeNumber(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getDeletedAt()
        );
    }
}

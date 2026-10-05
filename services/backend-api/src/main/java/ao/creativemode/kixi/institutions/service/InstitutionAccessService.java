package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Decides whether an account may author statements for an institution, so the
 * exams module can enforce the school rules without knowing how they are stored.
 */
@Service
public class InstitutionAccessService {

    private final InstitutionRepository institutionRepository;
    private final InstitutionSubjectRepository subjectLinks;
    private final InstitutionTeacherRepository teacherLinks;
    private final TeacherRepository teacherRepository;

    public InstitutionAccessService(
        InstitutionRepository institutionRepository,
        InstitutionSubjectRepository subjectLinks,
        InstitutionTeacherRepository teacherLinks,
        TeacherRepository teacherRepository
    ) {
        this.institutionRepository = institutionRepository;
        this.subjectLinks = subjectLinks;
        this.teacherLinks = teacherLinks;
        this.teacherRepository = teacherRepository;
    }

    /**
     * Completes when the account may author a statement of {@code subjectId} for
     * {@code institutionId}; fails otherwise. The institution must exist and
     * teach the subject; a non-administrator must be a teacher affiliated with it.
     */
    public Mono<Void> requireCanAuthor(Long accountId, boolean admin, Long institutionId, Long subjectId) {
        Mono<Void> institution = institutionRepository
            .findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .then();

        // Built lazily: each check only touches its repository once the previous
        // one has passed, so a failed check never reaches the later queries.
        Mono<Void> affiliation = Mono.defer(() -> admin
            ? Mono.<Void>empty()
            : teacherRepository
                .findByAccountIdAndDeletedAtIsNull(accountId)
                .switchIfEmpty(Mono.error(ApiException.forbidden("Only teachers can build statements")))
                .flatMap(teacher ->
                    teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(institutionId, teacher.getId()))
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(ApiException.forbidden("Teacher is not affiliated with this institution")))
                .then());

        Mono<Void> subject = Mono.defer(() -> subjectLinks
            .existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(institutionId, subjectId)
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(ApiException.unprocessableEntity("Subject is not taught by this institution")))
            .then());

        return institution.then(affiliation).then(subject);
    }
}

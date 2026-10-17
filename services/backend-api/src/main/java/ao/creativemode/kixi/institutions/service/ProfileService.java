package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.dto.profile.InstitutionCard;
import ao.creativemode.kixi.institutions.dto.profile.InstitutionProfile;
import ao.creativemode.kixi.institutions.dto.profile.TeacherCard;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Public profiles of schools (issue #331). Read-only, for any signed-in account. */
@Service
public class ProfileService {

    static final int MAX_RESULTS = 20;
    static final int MIN_QUERY = 2;

    private final InstitutionRepository institutions;
    private final TeacherRepository teachers;

    public ProfileService(InstitutionRepository institutions, TeacherRepository teachers) {
        this.institutions = institutions;
        this.teachers = teachers;
    }

    public Mono<InstitutionProfile> institution(Long id) {
        return institutions
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> Mono.zip(
                    institutions.countActiveTeachers(id),
                    institutions.countActiveStudents(id),
                    institutions.countVisibleStatements(id))
                .map(counts -> new InstitutionProfile(
                    institution.getId(),
                    institution.getCode(),
                    institution.getName(),
                    institution.getShortName(),
                    institution.getLogo(),
                    institution.isVerified(),
                    institution.getVerifiedAt(),
                    counts.getT1(),
                    counts.getT2(),
                    counts.getT3())));
    }

    public Flux<TeacherCard> teachersOf(Long institutionId) {
        return institutions
            .findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMapMany(institution -> teachers
                .findAllActiveByInstitutionId(institutionId)
                .map(teacher -> toCard(teacher, institution.isVerified())));
    }

    /** Schools matching the text; verified ones first. Too short a query answers nothing. */
    public Flux<InstitutionCard> searchInstitutions(String query) {
        String text = query == null ? "" : query.trim();
        if (text.length() < MIN_QUERY) {
            return Flux.empty();
        }
        return institutions.search(likePattern(text), MAX_RESULTS).map(ProfileService::toCard);
    }

    /** Lower-cased and with the LIKE wildcards of the text itself escaped, so "50%" is only "50%". */
    static String likePattern(String text) {
        String escaped = text.toLowerCase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    static InstitutionCard toCard(Institution i) {
        return new InstitutionCard(i.getId(), i.getCode(), i.getName(), i.getShortName(), i.getLogo(), i.isVerified());
    }

    static TeacherCard toCard(Teacher teacher, boolean verified) {
        String name = List.of(nullToEmpty(teacher.getFirstName()), nullToEmpty(teacher.getLastName()))
            .stream()
            .filter(part -> !part.isBlank())
            .reduce((a, b) -> a + " " + b)
            .orElse("Professor");
        return new TeacherCard(teacher.getId(), name, teacher.getPhoto(), teacher.getSpecialty(), verified);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

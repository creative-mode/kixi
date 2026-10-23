package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.dto.profile.InstitutionCard;
import ao.creativemode.kixi.institutions.dto.profile.InstitutionProfile;
import ao.creativemode.kixi.institutions.dto.profile.TeacherCard;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionTeacher;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final InstitutionTeacherRepository teacherLinks;

    public ProfileService(InstitutionRepository institutions, TeacherRepository teachers,
            InstitutionTeacherRepository teacherLinks) {
        this.institutions = institutions;
        this.teachers = teachers;
        this.teacherLinks = teacherLinks;
    }

    public Mono<InstitutionProfile> institution(Long id) {
        return institutions
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> Mono.zip(
                    institutions.countActiveTeachers(id),
                    institutions.countActiveStudents(id),
                    institutions.countVisibleStatements(id),
                    institutions.countPublishedRooms(id))
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
                    counts.getT3(),
                    counts.getT4())));
    }

    public Flux<TeacherCard> teachersOf(Long institutionId) {
        return institutions
            .findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMapMany(institution -> {
                InstitutionCard school = toCard(institution);
                return teachers
                    .findAllActiveByInstitutionId(institutionId)
                    .map(teacher -> toCard(teacher, List.of(school)));
            });
    }

    /** Schools matching the text; verified ones first. Too short a query answers nothing. */
    public Flux<InstitutionCard> searchInstitutions(String query) {
        String text = query == null ? "" : query.trim();
        if (text.length() < MIN_QUERY) {
            return Flux.empty();
        }
        return institutions.search(likePattern(text), MAX_RESULTS).map(ProfileService::toCard);
    }

    /**
     * Teachers matching the text by name or specialty, at any school (issue #406). A teacher at
     * two schools comes back once, carrying both, so the search never repeats a person the way
     * the school-by-school workaround did.
     */
    public Flux<TeacherCard> searchTeachers(String query) {
        String text = query == null ? "" : query.trim();
        if (text.length() < MIN_QUERY) {
            return Flux.empty();
        }
        return teachers.searchActiveByText(likePattern(text), MAX_RESULTS)
            .collectList()
            .flatMapMany(this::withSchools);
    }

    private Flux<TeacherCard> withSchools(List<Teacher> found) {
        if (found.isEmpty()) {
            return Flux.empty();
        }
        List<Long> teacherIds = found.stream().map(Teacher::getId).toList();
        return teacherLinks.findAllByTeacherIdInAndDeletedAtIsNull(teacherIds)
            .collectList()
            .flatMapMany(links -> schoolsById(links).flatMapMany(byId -> {
                Map<Long, List<InstitutionCard>> perTeacher = new HashMap<>();
                for (InstitutionTeacher link : links) {
                    InstitutionCard school = byId.get(link.getInstitutionId());
                    if (school != null) {
                        perTeacher.computeIfAbsent(link.getTeacherId(), key -> new ArrayList<>()).add(school);
                    }
                }
                perTeacher.values().forEach(schools -> schools.sort(Comparator.comparing(InstitutionCard::name)));
                return Flux.fromIterable(found)
                    .map(teacher -> toCard(teacher, perTeacher.getOrDefault(teacher.getId(), List.of())));
            }));
    }

    private Mono<Map<Long, InstitutionCard>> schoolsById(List<InstitutionTeacher> links) {
        List<Long> schoolIds = links.stream().map(InstitutionTeacher::getInstitutionId).distinct().toList();
        if (schoolIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return institutions.findAllById(schoolIds)
            .filter(institution -> institution.getDeletedAt() == null)
            .collectMap(Institution::getId, ProfileService::toCard);
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

    static TeacherCard toCard(Teacher teacher, List<InstitutionCard> schools) {
        String name = List.of(nullToEmpty(teacher.getFirstName()), nullToEmpty(teacher.getLastName()))
            .stream()
            .filter(part -> !part.isBlank())
            .reduce((a, b) -> a + " " + b)
            .orElse("Professor");
        boolean verified = schools.stream().anyMatch(InstitutionCard::verified);
        return new TeacherCard(
            teacher.getId(), teacher.getAccountId(), name, teacher.getPhoto(), teacher.getSpecialty(),
            verified, List.copyOf(schools));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

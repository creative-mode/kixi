package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.gamification.dto.UserBadgeResponse;
import ao.creativemode.kixi.gamification.service.GamificationService;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.institutions.dto.profile.InstitutionCard;
import ao.creativemode.kixi.institutions.dto.profile.PersonProfile;
import ao.creativemode.kixi.institutions.model.InstitutionStudent;
import ao.creativemode.kixi.institutions.model.InstitutionTeacher;
import ao.creativemode.kixi.institutions.model.ProfileSetting;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.ProfileSettingRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The profile of a person (issue #336). There are minors on the platform, so what a viewer sees is
 * decided by the person: public (any signed-in account), school (accounts that share a school) or
 * private (name and photo only). The person themselves and administrators always see everything.
 */
@Service
public class PersonProfileService {

    static final int MAX_BADGES = 12;

    private final UserRepository users;
    private final TeacherRepository teachers;
    private final InstitutionStudentRepository studentLinks;
    private final InstitutionTeacherRepository teacherLinks;
    private final InstitutionRepository institutions;
    private final ProfileSettingRepository settings;
    private final GamificationService gamification;

    public PersonProfileService(
        UserRepository users,
        TeacherRepository teachers,
        InstitutionStudentRepository studentLinks,
        InstitutionTeacherRepository teacherLinks,
        InstitutionRepository institutions,
        ProfileSettingRepository settings,
        GamificationService gamification
    ) {
        this.users = users;
        this.teachers = teachers;
        this.studentLinks = studentLinks;
        this.teacherLinks = teacherLinks;
        this.institutions = institutions;
        this.settings = settings;
        this.gamification = gamification;
    }

    public Mono<PersonProfile> person(Long viewerAccountId, boolean viewerIsAdmin, Long userId) {
        return users
            .findByIdAndDeletedAtIsNull(userId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Profile not found")))
            .flatMap(user -> Mono.zip(
                    settings.findVisibility(userId).defaultIfEmpty(ProfileSetting.SCHOOL),
                    schoolIdsOfAccount(user.getAccountId()),
                    isTeacher(user.getAccountId()))
                .flatMap(facts -> {
                    String visibility = facts.getT1();
                    Set<Long> theirSchools = facts.getT2();
                    boolean teacher = facts.getT3();
                    boolean owner = user.getAccountId() != null && user.getAccountId().equals(viewerAccountId);
                    return canSeeAll(owner, viewerIsAdmin, visibility, theirSchools, viewerAccountId)
                        .flatMap(all -> all
                            ? full(user, visibility, teacher, theirSchools)
                            : Mono.just(restricted(user, visibility, teacher)));
                }));
    }

    /** The visibility the signed-in person chose, "school" until they choose. */
    public Mono<String> myVisibility(Long accountId) {
        return myUserId(accountId)
            .flatMap(userId -> settings.findVisibility(userId).defaultIfEmpty(ProfileSetting.SCHOOL));
    }

    public Mono<String> setMyVisibility(Long accountId, String visibility) {
        return myUserId(accountId)
            .flatMap(userId -> settings.upsertVisibility(userId, visibility))
            .thenReturn(visibility);
    }

    private Mono<Long> myUserId(Long accountId) {
        return users
            .findByAccountIdAndDeletedAtIsNull(accountId)
            .next()
            .map(User::getId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Profile not found")));
    }

    private Mono<Boolean> canSeeAll(
        boolean owner, boolean admin, String visibility, Set<Long> theirSchools, Long viewerAccountId
    ) {
        if (owner || admin || ProfileSetting.PUBLIC.equals(visibility)) {
            return Mono.just(true);
        }
        if (ProfileSetting.PRIVATE.equals(visibility)) {
            return Mono.just(false);
        }
        return schoolIdsOfAccount(viewerAccountId)
            .map(viewerSchools -> viewerSchools.stream().anyMatch(theirSchools::contains));
    }

    private Mono<PersonProfile> full(User user, String visibility, boolean teacher, Set<Long> schoolIds) {
        Mono<List<InstitutionCard>> schools = institutions
            .findAllById(schoolIds)
            .filter(i -> i.getDeletedAt() == null)
            .map(ProfileService::toCard)
            .collectList();
        Mono<List<String>> badges = gamification
            .getBadges(user.getAccountId())
            .map(UserBadgeResponse::name)
            .take(MAX_BADGES)
            .collectList();
        Mono<long[]> stats = gamification
            .getStats(user.getAccountId())
            .map(s -> new long[] {s.level() == null ? 1 : s.level(), s.xp() == null ? 0 : s.xp()})
            .defaultIfEmpty(new long[] {1, 0});

        return Mono.zip(schools, badges, stats).map(parts -> new PersonProfile(
            user.getId(),
            displayName(user),
            user.getPhoto(),
            teacher ? "teacher" : "student",
            parts.getT1().stream().anyMatch(InstitutionCard::verified),
            false,
            visibility,
            parts.getT1(),
            (int) parts.getT3()[0],
            parts.getT3()[1],
            parts.getT2()));
    }

    private PersonProfile restricted(User user, String visibility, boolean teacher) {
        return new PersonProfile(
            user.getId(), displayName(user), user.getPhoto(), teacher ? "teacher" : "student",
            false, true, visibility, List.of(), null, null, List.of());
    }

    private Mono<Boolean> isTeacher(Long accountId) {
        return teachers.findByAccountIdAndDeletedAtIsNull(accountId).hasElement();
    }

    /** The schools an account belongs to, as a student (any of its users) or as a teacher. */
    private Mono<Set<Long>> schoolIdsOfAccount(Long accountId) {
        if (accountId == null) {
            return Mono.just(Set.of());
        }
        Flux<Long> asStudent = users
            .findByAccountIdAndDeletedAtIsNull(accountId)
            .flatMap(user -> studentLinks.findAllByUserIdAndDeletedAtIsNull(user.getId()))
            .map(InstitutionStudent::getInstitutionId);
        Flux<Long> asTeacher = teachers
            .findByAccountIdAndDeletedAtIsNull(accountId)
            .flatMapMany(teacher -> teacherLinks.findAllByTeacherIdAndDeletedAtIsNull(teacher.getId()))
            .map(InstitutionTeacher::getInstitutionId);
        return Flux.concat(asStudent, asTeacher).collect(Collectors.toSet());
    }

    static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "Utilizador" : name;
    }
}

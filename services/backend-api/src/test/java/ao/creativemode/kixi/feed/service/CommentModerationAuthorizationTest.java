package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.feed.enums.PostType;
import ao.creativemode.kixi.feed.model.Post;
import ao.creativemode.kixi.feed.model.PostComment;
import ao.creativemode.kixi.feed.repository.PostCommentRepository;
import ao.creativemode.kixi.feed.repository.PostFeedRepository;
import ao.creativemode.kixi.feed.repository.PostReactionRepository;
import ao.creativemode.kixi.feed.repository.PostReportRepository;
import ao.creativemode.kixi.feed.repository.PostRepository;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.institutions.service.InstitutionFeedScopeAuthorizer;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.DisplayNameReader;
import ao.creativemode.kixi.shared.service.InstitutionNameReader;
import ao.creativemode.kixi.shared.service.SimulationShowcaseReader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may moderate a comment, asked of the real {@link InstitutionFeedScopeAuthorizer}
 * (issue #313).
 *
 * <p>{@code PostServiceTest} mocks {@code FeedScopeAuthorizer}, and a mock cannot
 * answer the question that matters most here: what happens to an account with no
 * teacher profile. If {@code taughtClassesOf} ever degrades to an empty
 * {@code Mono} instead of an empty set, {@code flatMap} completes the whole
 * chain, the route answers 204 and <em>nothing is hidden</em> - a moderation that
 * silently does nothing, which is the hole this issue exists to close. Mocking the
 * collaborator is exactly what would let that regression through, so it is not
 * mocked here; only the repositories behind it are. Wiring several modules in one
 * test class is what the {@code ArchitectureTest} javadoc allows.</p>
 *
 * <p>The matrix: a moderator with no profile, a teacher with a profile but no
 * assignment, a teacher of the post's class, a teacher of another class, an ADMIN
 * with no profile at all, and a comment id that is not this post's.</p>
 */
@ExtendWith(MockitoExtension.class)
class CommentModerationAuthorizationTest {

    private static final Long TEACHER_ACCOUNT = 7L;
    private static final Long ADMIN_ACCOUNT = 1000L;
    /** Wears the TEACHER role with nothing behind it: no teacher row of its own. */
    private static final Long PROFILELESS_ACCOUNT = 55L;
    private static final Long TEACHER_ID = 900L;
    private static final Long MY_CLASS_ID = 10L;
    private static final Long OTHER_CLASS_ID = 99L;
    private static final Long MY_INSTITUTION_ID = 1L;
    private static final Long OTHER_INSTITUTION_ID = 2L;
    private static final Long POST_ID = 500L;
    private static final Long COMMENT_ID = 900L;

    @Mock private PostRepository postRepository;
    @Mock private PostCommentRepository commentRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private TeachingAssignmentRepository teachingAssignmentRepository;

    private PostService postService;

    private final Clock clock = Clock.fixed(
            LocalDateTime.of(2026, 10, 11, 12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        postService = new PostService(postRepository, mock(PostFeedRepository.class),
                mock(PostReactionRepository.class), commentRepository, mock(DisplayNameReader.class),
                mock(PostReportRepository.class),
                new InstitutionFeedScopeAuthorizer(mock(EnrollmentRepository.class), teachingAssignmentRepository,
                        teacherRepository, mock(ClassRepository.class)),
                mock(FeedRateLimiter.class), mock(SimulationShowcaseReader.class),
                mock(InstitutionNameReader.class), clock);

        // The common path, so each test below states only what it changes.
        lenient().when(teacherRepository.findByAccountIdAndDeletedAtIsNull(anyLong())).thenReturn(Mono.empty());
        lenient().when(commentRepository.save(any(PostComment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(postRepository.findByIdAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(post(MY_CLASS_ID, MY_INSTITUTION_ID)));
        lenient().when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID))
                .thenReturn(Mono.just(comment()));
    }

    /**
     * The role is there and the profile is not: 404, and the comment is not touched.
     *
     * <p>This is the row an empty {@code Mono} from the collaborator would turn into
     * a 204 with no write, so it is asserted on both sides: the status and the
     * absence of any save.</p>
     */
    @Test
    void aModeratorWithoutAProfileGets404AndNothingIsWritten() {
        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, PROFILELESS_ACCOUNT, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(commentRepository, never()).save(any());
    }

    /** Same account, this time with a teacher row and no assignment behind it. */
    @Test
    void aTeacherWithAProfileButNoAssignmentsGets404AndNothingIsWritten() {
        when(teacherRepository.findByAccountIdAndDeletedAtIsNull(TEACHER_ACCOUNT)).thenReturn(Mono.just(teacher()));
        when(teachingAssignmentRepository.findAllByTeacherIdAndDeletedAtIsNull(TEACHER_ID)).thenReturn(Flux.empty());

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, TEACHER_ACCOUNT, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(commentRepository, never()).save(any());
    }

    /** The positive side of the same rule: their own class, and the flag is written. */
    @Test
    void aTeacherOfThePostsClassHidesTheComment() {
        teacherTeaching(TEACHER_ACCOUNT, MY_CLASS_ID);

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, TEACHER_ACCOUNT, false)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.TRUE.equals(saved.getIsHidden())));
    }

    /** Another class of the same school is still another class: 404, nothing written. */
    @Test
    void aTeacherOfAnotherClassGets404AndNothingIsWritten() {
        teacherTeaching(TEACHER_ACCOUNT, OTHER_CLASS_ID);

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, TEACHER_ACCOUNT, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(commentRepository, never()).save(any());
    }

    /**
     * An ADMIN moderates in any school and is never narrowed by a class list, with or
     * without a profile: there is nothing to look up, and so nothing to degrade into.
     */
    @Test
    void anAdminWithoutAProfileHidesACommentInAnySchool() {
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(post(OTHER_CLASS_ID, OTHER_INSTITUTION_ID)));

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, ADMIN_ACCOUNT, true)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.TRUE.equals(saved.getIsHidden())));
        verifyNoInteractions(teacherRepository);
    }

    /** What was taken down comes back, and the same lookup finds the hidden row. */
    @Test
    void unhidingPutsACommentBack() {
        teacherTeaching(TEACHER_ACCOUNT, MY_CLASS_ID);
        PostComment hidden = comment();
        hidden.setIsHidden(true);
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID)).thenReturn(Mono.just(hidden));

        StepVerifier.create(postService.unhideComment(POST_ID, COMMENT_ID, TEACHER_ACCOUNT, false)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.FALSE.equals(saved.getIsHidden())));
    }

    /**
     * A comment id that is not this post's is a 404 even for a moderator who may act
     * on both posts: the {@code postId} is in the predicate, not in the caller's head.
     */
    @Test
    void aCommentThatIsNotOnThePostInTheUrlIs404() {
        teacherTeaching(TEACHER_ACCOUNT, MY_CLASS_ID);
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID)).thenReturn(Mono.empty());

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, TEACHER_ACCOUNT, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(commentRepository, never()).save(any());
    }

    private void teacherTeaching(Long accountId, Long... classIds) {
        when(teacherRepository.findByAccountIdAndDeletedAtIsNull(accountId)).thenReturn(Mono.just(teacher()));
        List<TeachingAssignment> assignments = Arrays.stream(classIds)
                .map(classId -> {
                    TeachingAssignment assignment = new TeachingAssignment();
                    assignment.setId(classId);
                    assignment.setTeacherId(TEACHER_ID);
                    assignment.setClassId(classId);
                    return assignment;
                })
                .toList();
        when(teachingAssignmentRepository.findAllByTeacherIdAndDeletedAtIsNull(TEACHER_ID))
                .thenReturn(Flux.fromIterable(assignments));
    }

    private static Teacher teacher() {
        Teacher teacher = new Teacher();
        teacher.setId(TEACHER_ID);
        teacher.setAccountId(TEACHER_ACCOUNT);
        return teacher;
    }

    private static Post post(Long classId, Long institutionId) {
        Post post = new Post();
        post.setId(POST_ID);
        post.setClassId(classId);
        post.setInstitutionId(institutionId);
        post.setType(PostType.TOPIC.getValue());
        post.setContent("A post");
        post.setIsHidden(false);
        return post;
    }

    private static PostComment comment() {
        PostComment comment = new PostComment();
        comment.setId(COMMENT_ID);
        comment.setPostId(POST_ID);
        comment.setAuthorAccountId(1L);
        comment.setContent("A comment");
        comment.setIsHidden(false);
        return comment;
    }
}
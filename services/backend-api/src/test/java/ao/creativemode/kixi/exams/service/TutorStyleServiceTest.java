package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Whose voice the tutor speaks in (issue #116).
 */
class TutorStyleServiceTest {

    /** The author's account id: statements.created_by references accounts, not teachers. */
    private static final Long AUTHOR = 500L;
    private static final Long CLASS = 7L;
    private static final Long SUBJECT = 9L;

    private TeachingAssignmentRepository assignments;
    private TutorStyleService service;

    @BeforeEach
    void setUp() {
        assignments = mock(TeachingAssignmentRepository.class);
        service = new TutorStyleService(assignments);
    }

    /** The style of the assignment the author holds for that class, subject and year. */
    @Test
    void readsTheStyleFromTheAuthorsAssignment() {
        when(assignments.findActiveForAuthor(AUTHOR, CLASS, SUBJECT))
                .thenReturn(Mono.just(assignment("explica com exemplos")));

        String style = service.styleOf(statement()).block();

        assertThat(style).isEqualTo("explica com exemplos");
    }

    /** Two teachers, two styles: the acceptance criterion of the issue. */
    @Test
    void twoAuthorsWithDifferentStylesAreReadDifferently() {
        when(assignments.findActiveForAuthor(AUTHOR, CLASS, SUBJECT))
                .thenReturn(Mono.just(assignment("prefere a fórmula primeiro")));
        String one = service.styleOf(statement()).block();

        Statement other = statement();
        other.setCreatedBy(99L);
        when(assignments.findActiveForAuthor(99L, CLASS, SUBJECT))
                .thenReturn(Mono.just(assignment("sempre muito breve")));
        String another = service.styleOf(other).block();

        assertThat(one).isNotEqualTo(another);
        assertThat(one).isEqualTo("prefere a fórmula primeiro");
        assertThat(another).isEqualTo("sempre muito breve");
    }

    /**
     * An author who never wrote a style gives the tutor nothing to say.
     *
     * <p>Not a failure: {@code compose(null)} then returns the prompt of before this
     * issue, which is exactly what should happen.</p>
     */
    @Test
    void anAuthorWithoutAStyleYieldsNothing() {
        when(assignments.findActiveForAuthor(anyLong(), anyLong(), anyLong()))
                .thenReturn(Mono.just(assignment(null)));

        assertThat(service.styleOf(statement()).block()).isEmpty();
    }

    /** Whitespace is not a voice. */
    @Test
    void aBlankStyleYieldsNothing() {
        when(assignments.findActiveForAuthor(anyLong(), anyLong(), anyLong()))
                .thenReturn(Mono.just(assignment("   ")));

        assertThat(service.styleOf(statement()).block()).isEmpty();
    }

    /** No assignment at all: the author does not teach that class and subject. */
    @Test
    void anAuthorWithNoAssignmentYieldsNothing() {
        when(assignments.findActiveForAuthor(anyLong(), anyLong(), anyLong()))
                .thenReturn(Mono.empty());

        assertThat(service.styleOf(statement()).block()).isEmpty();
    }

    /**
     * A statement with nobody to speak for does not reach the database.
     *
     * <p>A missing author, class or subject means the question has no answer, and
     * asking anyway would find a row by luck.</p>
     */
    @Test
    void aStatementWithNobodyToSpeakForAsksNobody() {
        for (Statement incomplete : new Statement[] {
            withoutAuthor(), withoutClass(), withoutSubject()}) {
            assertThat(service.styleOf(incomplete).block()).isEmpty();
        }

        verify(assignments, never()).findActiveForAuthor(anyLong(), anyLong(), anyLong());
    }

    private static Statement statement() {
        Statement statement = new Statement();
        statement.setCreatedBy(AUTHOR);
        statement.setClassId(CLASS);
        statement.setSubjectId(SUBJECT);
        return statement;
    }

    private static Statement withoutAuthor() {
        Statement statement = statement();
        statement.setCreatedBy(null);
        return statement;
    }

    private static Statement withoutClass() {
        Statement statement = statement();
        statement.setClassId(null);
        return statement;
    }

    private static Statement withoutSubject() {
        Statement statement = statement();
        statement.setSubjectId(null);
        return statement;
    }

    private static TeachingAssignment assignment(String style) {
        TeachingAssignment assignment = new TeachingAssignment();
        assignment.setTutorStyle(style);
        return assignment;
    }
}
package ao.creativemode.kixi.exams.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Query;

/**
 * The statement locks, pinned by the text of the query (issue #124).
 *
 * <p>{@link ExamRoomServiceTest} proves which lock method each path calls, and that is
 * the half that was already safe. The half nothing covered is what the query <em>is</em>:
 * a mock cannot tell {@code FOR SHARE} from {@code FOR UPDATE}, so reverting one for the
 * other left the suite green while putting a whole class back into a queue behind one row
 * lock, which is the bug this change exists to fix.</p>
 *
 * <p>The mutual exclusion itself was measured against PostgreSQL rather than asserted
 * here: two {@code FOR SHARE} on the same row both acquire, and a {@code FOR UPDATE}
 * against a held {@code FOR SHARE} blocks until the lock times out.</p>
 */
class StatementLockQueryTest {

    @Test
    void simulationsTakeTheSharedLockSoTheyDoNotQueueBehindEachOther() throws Exception {
        assertThat(queryOf("lockForShare"))
                .contains("FOR SHARE")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void openingARoomStillTakesTheExclusiveLock() throws Exception {
        assertThat(queryOf("lockForUpdate"))
                .contains("FOR UPDATE");
    }

    private String queryOf(String method) throws NoSuchMethodException {
        Method target = StatementRepository.class.getMethod(method, Long.class);
        Query annotation = target.getAnnotation(Query.class);
        assertThat(annotation)
                .as("%s must carry an explicit @Query", method)
                .isNotNull();
        return annotation.value();
    }
}
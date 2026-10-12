package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.model.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementReadScopeTest {

    private final StatementReadScope scope = new StatementReadScope(List.of(
            new StatementReadScope.Pair(1L, 10L),
            new StatementReadScope.Pair(1L, 11L)));

    @Test
    void theExactClassAndSubjectPair() {
        assertThat(scope.allows(statement(100L, 1L, 10L))).isTrue();
        assertThat(scope.allows(statement(100L, 1L, 12L))).isFalse();
        assertThat(scope.allows(statement(100L, 2L, 10L))).isFalse();
    }

    @Test
    void aClassWithoutSubjectNeedsTheTeacherToTeachTheClass() {
        assertThat(scope.allows(statement(100L, 1L, null))).isTrue();
        assertThat(scope.allows(statement(100L, 2L, null))).isFalse();
    }

    @Test
    void withoutAClassOnlyTheStatementsWithoutASchool() {
        assertThat(scope.allows(statement(null, null, null))).isTrue();
        assertThat(scope.allows(statement(100L, null, 10L))).isFalse();
    }

    @Test
    void noAssignmentsMeansOnlyTheStatementsNoClassOwns() {
        assertThat(StatementReadScope.NO_ASSIGNMENTS.allows(statement(null, null, null))).isTrue();
        assertThat(StatementReadScope.NO_ASSIGNMENTS.allows(statement(100L, 1L, 10L))).isFalse();
        assertThat(StatementReadScope.NO_ASSIGNMENTS.allows(null)).isFalse();
    }

    private static Statement statement(Long institutionId, Long classId, Long subjectId) {
        Statement statement = new Statement("P1", "Prova");
        statement.setInstitutionId(institutionId);
        statement.setClassId(classId);
        statement.setSubjectId(subjectId);
        return statement;
    }
}

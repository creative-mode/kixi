package ao.creativemode.kixi.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where an answer of the tutor came from (issue #115), for the screen to show
 * "fonte da resposta": the session's statement, the question in focus, and the
 * real catalog questions offered as similar exercises.
 *
 * @param kind {@code STATEMENT}, {@code QUESTION} or {@code CATALOG_QUESTION}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TutorSource(
    String kind,
    Long statementId,
    String statementTitle,
    Long questionId,
    Integer questionNumber
) {
    public static TutorSource statement(Long statementId, String title) {
        return new TutorSource("STATEMENT", statementId, title, null, null);
    }

    public static TutorSource question(Long statementId, String title, Long questionId, Integer number) {
        return new TutorSource("QUESTION", statementId, title, questionId, number);
    }

    public static TutorSource catalogQuestion(Long statementId, String title, Long questionId, Integer number) {
        return new TutorSource("CATALOG_QUESTION", statementId, title, questionId, number);
    }
}

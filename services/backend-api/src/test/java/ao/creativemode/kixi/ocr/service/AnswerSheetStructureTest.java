package ao.creativemode.kixi.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerSheetStructureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Question question(long id, int number, String type) {
        Question question = new Question();
        question.setId(id);
        question.setNumber(number);
        question.setQuestionType(type);
        return question;
    }

    private static QuestionOption option(long id, long questionId, String label) {
        QuestionOption option = new QuestionOption();
        option.setId(id);
        option.setQuestionId(questionId);
        option.setOptionLabel(label);
        return option;
    }

    @Test
    void theStructureComesFromTheStatementInQuestionOrderWithTheOptionLetters() {
        Statement statement = new Statement();
        statement.setId(2L);
        StatementWithQuestions content = new StatementWithQuestions(statement,
                List.of(question(7, 3, "open"), question(5, 1, "multiple_choice"), question(6, 2, "true_false")),
                List.of(option(1, 5, "C"), option(2, 5, "A"), option(3, 5, "B"), option(4, 6, "V")));

        JsonNode structure = AnswerSheetStructure.of(content, mapper);

        assertThat(structure.get("statementId").asLong()).isEqualTo(2L);
        JsonNode questions = structure.get("questions");
        assertThat(questions).hasSize(3);
        assertThat(questions.get(0).get("number").asInt()).isEqualTo(1);
        assertThat(questions.get(0).get("id").asText()).isEqualTo("5");
        assertThat(questions.get(0).get("type").asText()).isEqualTo("multiple_choice");
        assertThat(questions.get(0).get("options")).extracting(JsonNode::asText).containsExactly("A", "B", "C");
        assertThat(questions.get(1).has("options")).as("true/false has no letters to choose from").isFalse();
        assertThat(questions.get(2).get("type").asText()).isEqualTo("open");
    }

    @Test
    void aGroupQuestionHasNoAnswerOfItsOwnAndItsSubQuestionsKeepTheirNumbers() {
        Statement statement = new Statement();
        statement.setId(2L);
        StatementWithQuestions content = new StatementWithQuestions(statement,
                List.of(question(1, 1, "group"), question(2, 2, "open"), question(3, 3, "open")), List.of());

        JsonNode questions = AnswerSheetStructure.of(content, mapper).get("questions");

        assertThat(questions).extracting(node -> node.get("number").asInt()).containsExactly(2, 3);
    }
}

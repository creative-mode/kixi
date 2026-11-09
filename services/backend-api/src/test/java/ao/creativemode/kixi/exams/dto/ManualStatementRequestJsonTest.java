package ao.creativemode.kixi.exams.dto;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The exam builder sends the answer key of an open question as {@code modelAnswer}; the request has to carry it. */
class ManualStatementRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theModelAnswerOfAQuestionAndOfAnItemSurvivesDeserialization() throws Exception {
        var group = mapper.readValue("""
            {"text":"Resolva","type":"group","items":[
              {"label":"a","text":"Calcule x","type":"open","maxScore":2,"modelAnswer":"x = 3"}]}
            """, ManualStatementRequest.Question.class);
        var open = mapper.readValue("""
            {"text":"Defina inflação","type":"open","maxScore":4,"modelAnswer":"Subida geral dos preços"}
            """, ManualStatementRequest.Question.class);

        assertThat(open.modelAnswer()).isEqualTo("Subida geral dos preços");
        assertThat(group.modelAnswer()).isNull();
        assertThat(group.items().get(0).modelAnswer()).isEqualTo("x = 3");
    }

    @Test
    void aRequestWithoutModelAnswerStillDeserializes() throws Exception {
        var open = mapper.readValue("{\"text\":\"Defina\",\"maxScore\":1}", ManualStatementRequest.Question.class);

        assertThat(open.modelAnswer()).isNull();
    }
}

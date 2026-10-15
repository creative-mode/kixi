package ao.creativemode.kixi.ocr.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Issue #247: what the OCR service says about a question with alíneas reaches the exam builder
 * intact: the type, each alínea's label, text and own type, with the statement kept apart.
 */
class ExamExtractionResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The shape the OCR service returns for the OGI exam (trimmed to what the test reads). */
    private static final String OGI = """
        {"status":"success","requestId":"req-1","overallConfidence":0.91,
         "metadata":{"durationMinutes":{"value":90,"confidence":0.9},
                     "subjectName":{"value":"OGI","confidence":0.9},
                     "title":{"value":"Prova de Exame de OGI","confidence":0.9}},
         "questions":[
          {"number":"1","type":"dissertativa","confidence":0.95,
           "text":{"value":"O que consiste o aprovisionamento?","confidence":0.95},
           "subitems":[],"subitemsContent":[]},
          {"number":"3","type":"verdadeiro_falso","confidence":0.93,
           "text":{"value":"Diga quais são verdadeiras (V) ou falsas (F)","confidence":0.93},
           "subitems":["a","b"],
           "subitemsContent":[
             {"label":"a","text":"Deve comprar ao melhor preço.","cotacao":null,"type":"verdadeiro_falso"},
             {"label":"b","text":"A escolha só depois das aplicações.","cotacao":null,"type":"verdadeiro_falso"}]},
          {"number":"5","type":"composta","confidence":0.94,
           "text":{"value":"Uma empresa utiliza o Lote Económico...","confidence":0.94},
           "subitems":["a","b"],
           "subitemsContent":[
             {"label":"a","text":"Demonstre a expressão.","type":"dissertativa"},
             {"label":"b","text":"Calcule o custo anual e mensal.","type":"dissertativa"}]}]}
        """;

    @Test
    void theAlineasAndTheirTypesReachTheBuilderWithTheStatementApart() throws Exception {
        OcrResponse ocr = mapper.readValue(OGI, OcrResponse.class);

        ExamExtractionResponse exam = ExamExtractionResponse.fromOcrResponse(ocr);

        assertThat(exam.durationMinutes()).isEqualTo(90);
        assertThat(exam.subjectName()).isEqualTo("OGI");
        assertThat(exam.questions()).extracting(ExamExtractionResponse.QuestionData::type)
            .containsExactly("dissertativa", "verdadeiro_falso", "composta");

        ExamExtractionResponse.QuestionData trueFalse = exam.questions().get(1);
        assertThat(trueFalse.text()).as("the statement is the common one, without the items")
            .isEqualTo("Diga quais são verdadeiras (V) ou falsas (F)");
        assertThat(trueFalse.subitemsContent()).extracting(ExamExtractionResponse.SubitemData::label)
            .containsExactly("a", "b");
        assertThat(trueFalse.subitemsContent()).extracting(ExamExtractionResponse.SubitemData::type)
            .containsOnly("verdadeiro_falso");

        ExamExtractionResponse.QuestionData composed = exam.questions().get(2);
        assertThat(composed.subitemsContent()).extracting(ExamExtractionResponse.SubitemData::text)
            .containsExactly("Demonstre a expressão.", "Calcule o custo anual e mensal.");
        assertThat(exam.questions().get(0).subitemsContent()).isEmpty();
    }

    @Test
    void anAlineaTheOcrDidNotTypeStaysUntypedInsteadOfBeingGuessedHere() throws Exception {
        String json = """
            {"status":"success","questions":[{"number":"2","type":"composta",
             "text":{"value":"Enunciado","confidence":0.9},
             "subitemsContent":[{"label":"a","text":"Primeira"}]}]}
            """;

        ExamExtractionResponse exam = ExamExtractionResponse.fromOcrResponse(mapper.readValue(json, OcrResponse.class));

        assertThat(exam.questions().get(0).subitemsContent().get(0).type()).isNull();
    }

    @Test
    void theLegacyPathsStoreAQuestionWithItsAlineasAsPlainTextRatherThanDroppingThem() throws Exception {
        OcrResponse ocr = mapper.readValue(OGI, OcrResponse.class);

        OcrResponse.ExtractedQuestion composed = ocr.questions().get(2);

        assertThat(composed.textWithSubitems())
            .isEqualTo("Uma empresa utiliza o Lote Económico...\na) Demonstre a expressão.\nb) Calcule o custo anual e mensal.");
        assertThat(ocr.questions().get(0).textWithSubitems()).isEqualTo("O que consiste o aprovisionamento?");
    }
}

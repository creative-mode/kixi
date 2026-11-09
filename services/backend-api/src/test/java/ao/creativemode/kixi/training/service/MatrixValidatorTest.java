package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.training.dto.MatrixItemRequest;
import ao.creativemode.kixi.training.dto.MatrixRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** As regras da matriz da prova (kixi#729): temas, duplicados e a soma das cotações. */
class MatrixValidatorTest {

    private static MatrixItemRequest item(Long id, String name, int count, Double points, String level) {
        return new MatrixItemRequest(id, name, count, points, level, null);
    }

    private static MatrixRequest matrix(Double total, MatrixItemRequest... items) {
        return new MatrixRequest("Matriz do 1.º trimestre", total, List.of(items));
    }

    @Test
    void pointsThatAddUpToTheTotalPass() {
        MatrixValidator.Validated validated = MatrixValidator.validate(matrix(null,
                item(1L, null, 3, 8.0, "conhecimento"), item(2L, null, 4, 7.5, "COMPREHENSION"), item(null, "Estatística", 2, 4.5, "Aplicação")));
        assertThat(validated.totalPoints()).isEqualByComparingTo("20");
        assertThat(validated.items()).extracting(MatrixValidator.Item::level)
                .containsExactly(CognitiveLevel.KNOWLEDGE, CognitiveLevel.COMPREHENSION, CognitiveLevel.APPLICATION);
    }

    @Test
    void theSumToleratesAHundredth() {
        MatrixValidator.validate(matrix(20.0, item(1L, null, 1, 10.005, null), item(2L, null, 1, 10.0, null)));
    }

    @Test
    void aSumThatDoesNotMatchIsRefusedWithAClearMessage() {
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(20.0, item(1L, null, 3, 8.0, null), item(2L, null, 4, 8.0, null))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("A soma das cotações (16) tem de ser igual ao total da prova (20)");
    }

    @Test
    void aCustomScaleIsHonoured() {
        MatrixValidator.validate(matrix(100.0, item(1L, null, 3, 60.0, null), item(2L, null, 4, 40.0, null)));
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(10.0, item(1L, null, 3, 8.0, null), item(2L, null, 4, 3.5, null))))
                .hasMessageContaining("(11,5)").hasMessageContaining("(10)");
    }

    @Test
    void withoutAnyPointsThereIsNothingToAdd() {
        MatrixValidator.validate(matrix(null, item(1L, null, 3, null, null), item(2L, null, 4, null, null)));
    }

    @Test
    void repeatedTopicsAreRefused() {
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(1L, null, 3, null, null), item(1L, null, 4, null, null))))
                .hasMessageContaining("aparece repetido");
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null,
                item(null, "Funções", 3, null, null), item(null, "  funcoes ", 4, null, null))))
                .hasMessageContaining("«funcoes»").hasMessageContaining("aparece repetido");
    }

    @Test
    void atLeastOneTopicAndEveryTopicNeedsAQuestionAndAnIdentity() {
        assertThatThrownBy(() -> MatrixValidator.validate(new MatrixRequest(null, null, List.of())))
                .hasMessageContaining("pelo menos um tema");
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(1L, null, 0, null, null))))
                .hasMessageContaining("pelo menos uma questão");
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(null, "  ", 2, null, null))))
                .hasMessageContaining("topicId ou de um topicName");
    }

    @Test
    void anUnknownLevelAndNegativePointsAreRefused() {
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(1L, null, 2, null, "difícil"))))
                .hasMessageContaining("Nível cognitivo desconhecido «difícil»");
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(1L, null, 2, -1.0, null))))
                .hasMessageContaining("não pode ser negativa");
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(0.0, item(1L, null, 2, null, null))))
                .hasMessageContaining("total de pontos");
    }

    @Test
    void limitsOnPointsQuestionsAndNames() {
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(10000.0, item(1L, null, 1, null, null))))
                .hasMessageContaining("total de pontos da prova não pode passar de 9999,99");
        MatrixValidator.validate(matrix(9999.99, item(1L, null, 1, 9999.99, null)));
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(1L, null, 1, 10000.0, null))))
                .hasMessageContaining("cotação do tema").hasMessageContaining("não pode passar de 9999,99");
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(1L, null, 101, null, null))))
                .hasMessageContaining("não pode ter mais de 100 questões");
        MatrixValidator.validate(matrix(null, item(1L, null, 100, null, null)));
        assertThatThrownBy(() -> MatrixValidator.validate(matrix(null, item(null, "x".repeat(201), 1, null, null))))
                .isInstanceOf(ApiException.class).hasMessageContaining("não pode passar de 200 caracteres");
        MatrixValidator.validate(matrix(null, item(null, "x".repeat(200), 1, null, null)));
    }

    @Test
    void levelsParseInPortugueseAndEnglishWithoutAccents() {
        assertThat(CognitiveLevel.parse("Compreensão")).contains(CognitiveLevel.COMPREHENSION);
        assertThat(CognitiveLevel.parse("analise")).contains(CognitiveLevel.ANALYSIS);
        assertThat(CognitiveLevel.parse("Application")).contains(CognitiveLevel.APPLICATION);
        assertThat(CognitiveLevel.parse("???")).isEmpty();
    }

    @Test
    void questionTypesAreCleaned() {
        MatrixValidator.Validated validated = MatrixValidator.validate(new MatrixRequest(null, 10.0,
                List.of(new MatrixItemRequest(1L, null, 2, BigDecimal.TEN.doubleValue(), null, List.of(" múltipla escolha ", "", "dissertativa")))));
        assertThat(validated.items().get(0).questionTypes()).containsExactly("múltipla escolha", "dissertativa");
    }
}

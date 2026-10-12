package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.exams.service.SimilarQuestionService.SimilarQuestion;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Issue #115: real catalog questions for "exercícios semelhantes". */
class SimilarQuestionServiceTest {

    private QuestionRepository questions;
    private QuestionOptionRepository options;
    private StatementRepository statements;
    private SimilarQuestionService service;

    @BeforeEach
    void setUp() {
        questions = mock(QuestionRepository.class);
        options = mock(QuestionOptionRepository.class);
        statements = mock(StatementRepository.class);
        service = new SimilarQuestionService(questions, options, statements);
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(anyCollection())).thenReturn(Flux.empty());
        when(statements.findAllById(anyIterable())).thenReturn(Flux.just(statement(8L, "Exame de 2023")));
    }

    @Test
    void theQuestionsThatShareMostWordsWithTheFocusComeFirst() {
        when(questions.findCatalogCandidates(5L, 7L, SimilarQuestionService.CANDIDATES)).thenReturn(Flux.just(
                question(71L, 8L, "Qual é a capital de Angola?", "multiple_choice"),
                question(72L, 8L, "Calcula a derivada da função quadrática", "open"),
                question(73L, 8L, "Determina a derivada da função exponencial", "multiple_choice")));

        List<SimilarQuestion> found = service.similarTo(statement(7L, "Exame"),
                question(41L, 7L, "Calcula a derivada da função cúbica", "multiple_choice"), 2).block();

        assertThat(found).extracting(SimilarQuestion::questionId).containsExactly(72L, 73L);
        assertThat(found.get(0).statementTitle()).isEqualTo("Exame de 2023");
    }

    @Test
    void onATieTheSameTypeWins() {
        when(questions.findCatalogCandidates(anyLong(), anyLong(), anyInt())).thenReturn(Flux.just(
                question(72L, 8L, "Derivada da função", "open"),
                question(73L, 8L, "Derivada da função", "multiple_choice")));

        List<SimilarQuestion> found = service.similarTo(statement(7L, "Exame"),
                question(41L, 7L, "Derivada da função", "multiple_choice"), 1).block();

        assertThat(found).extracting(SimilarQuestion::questionId).containsExactly(73L);
    }

    @Test
    void theOptionsTravelWithoutTheAnswerKeyInTheirOrder() {
        when(questions.findCatalogCandidates(anyLong(), anyLong(), anyInt()))
                .thenReturn(Flux.just(question(72L, 8L, "Quanto é 3+3?", "multiple_choice")));
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(anyCollection())).thenReturn(Flux.just(
                option(72L, "B", "9", 1, false), option(72L, "A", "6", 0, true)));

        SimilarQuestion found = service.similarTo(statement(7L, "Exame"),
                question(41L, 7L, "Quanto é 2+2?", "multiple_choice"), 3).block().get(0);

        assertThat(found.choices()).extracting(SimilarQuestionService.Choice::label).containsExactly("A", "B");
        assertThat(SimilarQuestionService.Choice.class.getRecordComponents())
                .extracting(component -> component.getName()).containsExactly("label", "text");
    }

    @Test
    void withoutASubjectThereIsNothingToCompareWith() {
        Statement noSubject = statement(7L, "Exame");
        noSubject.setSubjectId(null);

        assertThat(service.similarTo(noSubject, question(41L, 7L, "x", "open"), 3).block()).isEmpty();
        verify(questions, never()).findCatalogCandidates(anyLong(), anyLong(), anyInt());
    }

    @Test
    void anEmptyCatalogIsAnEmptyList() {
        when(questions.findCatalogCandidates(eq(5L), eq(7L), anyInt())).thenReturn(Flux.empty());

        assertThat(service.similarTo(statement(7L, "Exame"), question(41L, 7L, "x", "open"), 3).block()).isEmpty();
    }

    @Test
    void wordsIgnoreCaseAccentsAndShortWords() {
        assertThat(SimilarQuestionService.words("Função DERIVADA de x, ação!"))
                .containsExactlyInAnyOrder("funcao", "derivada", "acao");
    }

    private static Statement statement(Long id, String title) {
        Statement statement = new Statement();
        statement.setId(id);
        statement.setTitle(title);
        statement.setSubjectId(5L);
        return statement;
    }

    private static Question question(Long id, Long statementId, String text, String type) {
        Question question = new Question();
        question.setId(id);
        question.setStatementId(statementId);
        question.setNumber(id.intValue() % 10);
        question.setText(text);
        question.setQuestionType(type);
        return question;
    }

    private static QuestionOption option(Long questionId, String label, String text, int order, boolean correct) {
        QuestionOption option = new QuestionOption();
        option.setQuestionId(questionId);
        option.setOptionLabel(label);
        option.setOptionText(text);
        option.setOrderIndex(order);
        option.setIsCorrect(correct);
        return option;
    }
}

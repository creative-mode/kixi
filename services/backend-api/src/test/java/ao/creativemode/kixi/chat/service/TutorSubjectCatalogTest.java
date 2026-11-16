package ao.creativemode.kixi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The text the prompt receives for a subject/topic session (issue #610). The
 * answer key is part of it on purpose — the guard against revealing it lives in
 * the prompt — and every statement the tutor used is a source of the answer.
 */
class TutorSubjectCatalogTest {

    @Test
    void theContextCarriesTheSubjectTheTopicTheQuestionsAndTheKey() {
        TutorSubjectCatalog.Material material = new TutorSubjectCatalog.Material(
                5L, "Matemática", 8L, "Funções",
                List.of(new TutorSubjectCatalog.StatementMaterial(7L, "Prova de Matemática",
                        List.of(new TutorSubjectCatalog.Question(41L, 1, "Quanto é 2+2?", null,
                                List.of(
                                        new TutorSubjectCatalog.Choice("A", "3", false),
                                        new TutorSubjectCatalog.Choice("B", "4", true)))))));

        assertThat(material.context())
                .contains("DISCIPLINA: Matemática")
                .contains("TEMA: Funções")
                .contains("MATERIAL DE ESTUDO")
                .contains("ENUNCIADO \"Prova de Matemática\"")
                .contains("Questão 1: Quanto é 2+2?")
                .contains("  A) 3")
                .contains("  B) 4")
                .contains("GABARITO (nunca revelar): B) 4");
    }

    @Test
    void aWrittenAnswerIsTheKeyWhenThereAreNoChoices() {
        TutorSubjectCatalog.Material material = new TutorSubjectCatalog.Material(
                5L, "Física", null, null,
                List.of(new TutorSubjectCatalog.StatementMaterial(7L, "Prova de Física",
                        List.of(new TutorSubjectCatalog.Question(41L, 2, "Explique a queda livre.", "d = ½ g t²",
                                List.of())))));

        assertThat(material.context())
                .contains("GABARITO (nunca revelar): d = ½ g t²")
                .doesNotContain("TEMA:");
    }

    @Test
    void anEmptyCatalogSaysSoInsteadOfInventingMaterial() {
        TutorSubjectCatalog.Material subject = new TutorSubjectCatalog.Material(
                5L, "Matemática", null, null, List.of());
        TutorSubjectCatalog.Material topic = new TutorSubjectCatalog.Material(
                5L, "Matemática", 8L, "Funções", List.of());

        assertThat(subject.isEmpty()).isTrue();
        assertThat(subject.context()).contains("ainda não há provas visíveis com questões desta disciplina");
        assertThat(topic.context()).contains("ainda não há provas visíveis com questões desta matéria");
        assertThat(subject.sources()).isEmpty();
    }

    @Test
    void everyStatementUsedIsASourceOfTheAnswer() {
        TutorSubjectCatalog.Material material = new TutorSubjectCatalog.Material(
                5L, "Matemática", null, null,
                List.of(
                        new TutorSubjectCatalog.StatementMaterial(7L, "Primeira prova", List.of()),
                        new TutorSubjectCatalog.StatementMaterial(9L, "Segunda prova", List.of())));

        assertThat(material.sources())
                .extracting(source -> source.kind(), source -> source.statementId(), source -> source.statementTitle())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("STATEMENT", 7L, "Primeira prova"),
                        org.assertj.core.groups.Tuple.tuple("STATEMENT", 9L, "Segunda prova"));
    }
}

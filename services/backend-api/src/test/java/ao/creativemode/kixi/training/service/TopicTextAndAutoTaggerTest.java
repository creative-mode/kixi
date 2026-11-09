package ao.creativemode.kixi.training.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** O «slug» dos temas e a etiquetagem automática por palavras-chave (kixi#729). */
class TopicTextAndAutoTaggerTest {

    @Test
    void slugHasNoAccentsNoCapitalsAndNoPunctuation() {
        assertThat(TopicText.slug("Funções Quadráticas")).isEqualTo("funcoes-quadraticas");
        assertThat(TopicText.slug("  Análise  Combinatória!! ")).isEqualTo("analise-combinatoria");
        assertThat(TopicText.slug("Ação & Reação (Newton)")).isEqualTo("acao-reacao-newton");
        assertThat(TopicText.slug("???")).isEmpty();
        assertThat(TopicText.slug(null)).isEmpty();
    }

    @Test
    void normalizeKeepsLettersButDropsAccentsAndCase() {
        assertThat(TopicText.normalize("  ÁGUA   e  Ião ")).isEqualTo("agua e iao");
    }

    private final AutoTagger tagger = new AutoTagger(List.of(
            new AutoTagger.Topic(1L, "Funções", "função, gráfico, domínio"),
            new AutoTagger.Topic(2L, "Geometria", "triângulo, área, perímetro"),
            new AutoTagger.Topic(3L, "Estatística", "média; moda; mediana")));

    @Test
    void theTopicWithMostHitsWins() {
        assertThat(tagger.best("Calcule a área e o perímetro do triângulo ABC.")).contains(2L);
        assertThat(tagger.best("Esboce o GRÁFICO da FUNÇÃO e indique o domínio.")).contains(1L);
    }

    @Test
    void theOptionsTextCountsToo() {
        assertThat(tagger.best("Qual é o valor?", "A) a média  B) a moda")).contains(3L);
    }

    @Test
    void noHitsMeansNoTopic() {
        assertThat(tagger.best("Resolva a equação x + 1 = 2.")).isEmpty();
        assertThat(tagger.best((String) null)).isEmpty();
    }

    @Test
    void tooShortTermsDoNotMatchByAccident() {
        AutoTagger shortTerms = new AutoTagger(List.of(new AutoTagger.Topic(9L, "Ab", "x, de")));
        assertThat(shortTerms.best("o valor de x é de 3")).isEmpty();
    }

    @Test
    void aTieGoesToTheLowestId() {
        AutoTagger tie = new AutoTagger(List.of(
                new AutoTagger.Topic(5L, "Raízes", null), new AutoTagger.Topic(4L, "Potências", null)));
        assertThat(tie.best("raízes e potências")).contains(4L);
    }

    @Test
    void moreHitsBeatTheFirstMatch() {
        assertThat(tagger.best("A média e a moda de uma função")).contains(3L);
    }

    @Test
    void parentTextAlsoCounts() {
        assertThat(tagger.best("a) Justifique.", null, "Considere o triângulo ABC com área 12.")).contains(2L);
    }
}

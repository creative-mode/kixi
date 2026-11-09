package ao.creativemode.kixi.training.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Escolhe o tema de uma questão comparando o seu texto (e o das opções) com o nome e as palavras-chave de cada tema
 * (kixi#729). Tudo normalizado, sem acentos e em minúsculas. O tema com mais acertos ganha; sem acertos não há tema.
 * Num empate fica o tema de id mais baixo, para o resultado ser sempre o mesmo.
 */
public final class AutoTagger {

    /** Um tema candidato: as palavras-chave vêm separadas por vírgula (ou ponto e vírgula). */
    public record Topic(Long id, String name, String keywords) {}

    /** Termos mais curtos do que isto dão acertos por acaso («x», «de»): não contam. */
    static final int MIN_TERM_LENGTH = 3;

    private record Prepared(Long id, Set<String> terms) {}

    private final List<Prepared> topics;

    public AutoTagger(List<Topic> topics) {
        List<Prepared> prepared = new ArrayList<>();
        for (Topic topic : topics) {
            Set<String> terms = new LinkedHashSet<>();
            addTerm(terms, topic.name());
            if (topic.keywords() != null) {
                for (String keyword : topic.keywords().split("[,;]")) {
                    addTerm(terms, keyword);
                }
            }
            if (!terms.isEmpty()) {
                prepared.add(new Prepared(topic.id(), terms));
            }
        }
        prepared.sort(java.util.Comparator.comparing(Prepared::id));
        this.topics = List.copyOf(prepared);
    }

    private static void addTerm(Set<String> terms, String raw) {
        String term = TopicText.normalize(raw);
        if (term.length() >= MIN_TERM_LENGTH) {
            terms.add(term);
        }
    }

    /** O tema com mais termos presentes no texto, ou vazio se nenhum aparece. */
    public Optional<Long> best(String... texts) {
        StringBuilder joined = new StringBuilder();
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                joined.append(' ').append(TopicText.normalize(text));
            }
        }
        String haystack = joined.toString();
        Long best = null;
        int bestScore = 0;
        for (Prepared topic : topics) {
            int score = 0;
            for (String term : topic.terms()) {
                if (haystack.contains(term)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = topic.id();
            }
        }
        return Optional.ofNullable(best);
    }
}

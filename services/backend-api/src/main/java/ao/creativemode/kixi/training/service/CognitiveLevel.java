package ao.creativemode.kixi.training.service;

import java.util.Optional;

/** Nível cognitivo (tipo Bloom) de um tema da matriz; aceita-se em português e em inglês. */
public enum CognitiveLevel {
    KNOWLEDGE, COMPREHENSION, APPLICATION, ANALYSIS;

    /** «conhecimento», «compreensão», «aplicação», «análise» ou o nome em inglês, sem distinguir maiúsculas nem acentos. */
    public static Optional<CognitiveLevel> parse(String text) {
        String key = TopicText.normalize(text);
        return switch (key) {
            case "conhecimento", "knowledge" -> Optional.of(KNOWLEDGE);
            case "compreensao", "comprehension" -> Optional.of(COMPREHENSION);
            case "aplicacao", "application" -> Optional.of(APPLICATION);
            case "analise", "analysis" -> Optional.of(ANALYSIS);
            default -> Optional.empty();
        };
    }
}

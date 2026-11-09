package ao.creativemode.kixi.training.service;

import java.text.Normalizer;
import java.util.Locale;

/** Normalização de texto para temas (kixi#729): sem acentos, em minúsculas, para comparar e para o «slug». */
public final class TopicText {

    private TopicText() {}

    /** Minúsculas e sem acentos, com os espaços repetidos reduzidos a um. Nulo dá o texto vazio. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** O nome normalizado de um tema como identificador: «Funções Quadráticas» dá {@code funcoes-quadraticas}. */
    public static String slug(String name) {
        return normalize(name)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}

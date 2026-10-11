package ao.creativemode.kixi.chat.service;

/**
 * Turns a teacher's free text into something that can sit inside a system prompt
 * without being able to act as one (issue #116).
 *
 * <p>Structural, not a word filter. A blacklist has to be extended for every new way
 * of saying the same thing, and it punishes the styles we actually want: a teacher who
 * writes "ignora as fórmulas e explica só com exemplos" would have their own voice cut
 * out for containing a word the blocklist dislikes. Here the shape of the text is
 * destroyed instead, and the words are left alone.</p>
 *
 * <p>The one thing that survives everything is the length cut. A style that keeps
 * rewriting the instructions after truncation is still just a style, because the
 * instructions were written before it and the closing note after it says so again.</p>
 */
public final class TutorStyleSanitizer {

    /** The column is {@code VARCHAR(100)}; the prompt is not the place to overflow it. */
    static final int MAX_LENGTH = 100;

    /**
     * What a model reads when a message claims to be the system or the user. Flattened
     * rather than dropped: the teacher wrote those words as part of a style, and
     * removing them silently would edit somebody's voice. Replacing them with a
     * placeholder keeps the text honest — the reader can see something was rewritten.
     */
    private static final String[][] ROLE_MARKERS = {
        {"system:", "sistema:"},
        {"assistant:", "assistente:"},
        {"user:", "utilizador:"},
        {"<|im_start|>", ""},
        {"<|im_end|>", ""},
        {"<|system|>", ""},
        {"<|user|>", ""},
        {"<|assistant|>", ""},
    };

    /** The delimiters this class itself writes, which must not survive inside the text. */
    private static final String[] DELIMITERS = {"---", "###", "«", "»"};

    private TutorStyleSanitizer() { }

    /**
     * One line, no role markers, no delimiters, at most {@link #MAX_LENGTH} characters.
     *
     * <p>Blank in, blank out. A style that sanitizes to nothing is not an error and not
     * an empty voice: it is the same situation as never having had one.</p>
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String text = raw;

        // Fenced code first, while the markers are still findable: stripping the backticks
        // afterwards would leave the body behind as ordinary text.
        text = text.replaceAll("(?s)```.*?```", " ");
        text = text.replace("```", " ");

        for (String[] marker : ROLE_MARKERS) {
            text = text.replaceAll("(?i)\\s*" + java.util.regex.Pattern.quote(marker[0]) + "\\s*",
                    marker[1].isEmpty() ? " " : " " + marker[1] + " ");
        }
        for (String delimiter : DELIMITERS) {
            text = text.replace(delimiter, " ");
        }

        // Newlines become spaces so the style cannot start a paragraph of its own.
        text = text.replaceAll("\\s+", " ").trim();

        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH).trim();
        }
        return text;
    }
}
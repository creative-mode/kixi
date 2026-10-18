package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.feed.dto.post.PostBlock;
import ao.creativemode.kixi.shared.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * As regras dos blocos de um post (issue #347): o que cada tipo aceita, os limites, o resumo em
 * texto simples e a ida e volta para JSON. Sem estado e sem I/O, para se poder testar à parte.
 *
 * <p>Uma imagem só pode apontar para o armazenamento do próprio Kixi
 * ({@code /uploads/images/post/...}): um endereço externo deixava qualquer pessoa pôr um
 * pixel de rastreio ou conteúdo alheio no feed da turma.</p>
 */
public final class PostBlocks {

    public static final int MAX_BLOCKS = 20;
    public static final int MAX_IMAGES = 6;
    public static final int MAX_TOTAL_TEXT = 4000;
    public static final int MAX_SUMMARY = 2000;

    private static final Pattern IMAGE_URL = Pattern.compile("/uploads/images/post/[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.(png|jpe?g|webp)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TONES = Set.of("info", "tip", "warning");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<PostBlock>> LIST = new TypeReference<>() { };

    private PostBlocks() { }

    /** Recusa, com um 400, o que não cumpre as regras; devolve os blocos já aparados. */
    public static List<PostBlock> validate(List<PostBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw ApiException.badRequest("A post has at most " + MAX_BLOCKS + " blocks");
        }
        int images = 0;
        int total = 0;
        List<PostBlock> clean = new java.util.ArrayList<>();
        for (PostBlock raw : blocks) {
            if (raw == null || raw.type() == null) {
                throw ApiException.badRequest("Every block needs a type");
            }
            PostBlock block = switch (raw.type()) {
                case "heading" -> only(raw, text(raw, 120), null);
                case "text" -> only(raw, text(raw, 2000), null);
                case "formula" -> only(raw, text(raw, 300), null);
                case "callout" -> callout(raw);
                case "list" -> list(raw);
                case "image" -> image(raw);
                case "choice" -> choice(raw);
                default -> throw ApiException.badRequest("Unknown block type: " + raw.type());
            };
            if (block.type().equals("image")) {
                images++;
            }
            total += weight(block);
            clean.add(block);
        }
        if (images > MAX_IMAGES) {
            throw ApiException.badRequest("A post has at most " + MAX_IMAGES + " images");
        }
        if (total > MAX_TOTAL_TEXT) {
            throw ApiException.badRequest("The text of a post has at most " + MAX_TOTAL_TEXT + " characters");
        }
        return List.copyOf(clean);
    }

    /** O texto simples dos blocos, que fica em {@code content}: o que um cliente sem blocos mostra. */
    public static String summary(List<PostBlock> blocks) {
        StringBuilder out = new StringBuilder();
        for (PostBlock b : blocks) {
            switch (b.type()) {
                case "heading", "text", "formula", "callout" -> line(out, b.text());
                case "list" -> b.items().forEach(item -> line(out, "- " + item));
                case "choice" -> {
                    line(out, b.text());
                    b.items().forEach(item -> line(out, "- " + item));
                }
                default -> { }
            }
        }
        String text = out.toString().trim();
        return text.length() > MAX_SUMMARY ? text.substring(0, MAX_SUMMARY).trim() : text;
    }

    public static String toJson(List<PostBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the post blocks", e);
        }
    }

    /** Lê os blocos guardados; um valor ilegível dá uma lista vazia e o post mostra o resumo. */
    public static List<PostBlock> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    // ---- por tipo -------------------------------------------------------------------------

    private static String text(PostBlock b, int max) {
        String value = b.text() == null ? "" : b.text().trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("A " + b.type() + " block needs text");
        }
        if (value.length() > max) {
            throw ApiException.badRequest("A " + b.type() + " block has at most " + max + " characters");
        }
        return value;
    }

    private static PostBlock only(PostBlock b, String text, String tone) {
        return new PostBlock(b.type(), text, null, null, tone, null, null, null, null, null);
    }

    private static PostBlock callout(PostBlock b) {
        String tone = b.tone() == null || b.tone().isBlank() ? "info" : b.tone();
        if (!TONES.contains(tone)) {
            throw ApiException.badRequest("A callout tone must be one of " + TONES);
        }
        return only(b, text(b, 500), tone);
    }

    private static List<String> items(PostBlock b, int min, int max, int maxItem) {
        List<String> items = b.items() == null ? List.of() : b.items().stream()
                .map(i -> i == null ? "" : i.trim()).toList();
        if (items.size() < min || items.size() > max || items.stream().anyMatch(i -> i.isEmpty() || i.length() > maxItem)) {
            throw ApiException.badRequest("A " + b.type() + " block needs " + min + " to " + max
                    + " items of up to " + maxItem + " characters");
        }
        return items;
    }

    private static PostBlock list(PostBlock b) {
        return new PostBlock("list", null, items(b, 1, 10, 200), Boolean.TRUE.equals(b.ordered()),
                null, null, null, null, null, null);
    }

    private static PostBlock image(PostBlock b) {
        String url = b.url() == null ? "" : b.url().trim();
        if (!IMAGE_URL.matcher(url).matches() || url.contains("..")) {
            throw ApiException.badRequest("An image block must use an image uploaded to the post storage");
        }
        return new PostBlock("image", null, null, null, null, url, trim(b.alt(), 150, "alt"),
                trim(b.caption(), 150, "caption"), null, null);
    }

    private static PostBlock choice(PostBlock b) {
        List<String> options = items(b, 2, 5, 150);
        if (b.correct() == null || b.correct() < 0 || b.correct() >= options.size()) {
            throw ApiException.badRequest("A choice block needs the position of the right option");
        }
        return new PostBlock("choice", text(b, 300), options, null, null, null, null, null,
                b.correct(), trim(b.explanation(), 500, "explanation"));
    }

    private static String trim(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw ApiException.badRequest("The " + field + " has at most " + max + " characters");
        }
        return v;
    }

    private static int weight(PostBlock b) {
        int n = b.text() == null ? 0 : b.text().length();
        if (b.items() != null) {
            n += b.items().stream().mapToInt(String::length).sum();
        }
        return n + (b.explanation() == null ? 0 : b.explanation().length());
    }

    private static void line(StringBuilder out, String text) {
        if (text != null && !text.isBlank()) {
            out.append(text.trim()).append('\n');
        }
    }
}

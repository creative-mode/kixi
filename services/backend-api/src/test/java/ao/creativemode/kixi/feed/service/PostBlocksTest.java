package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.feed.dto.post.PostBlock;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As regras dos blocos de um post (issue #347). */
class PostBlocksTest {

    private static PostBlock text(String t) {
        return new PostBlock("text", t, null, null, null, null, null, null, null, null);
    }

    private static PostBlock image(String url) {
        return new PostBlock("image", null, null, null, null, url, "Esquema", null, null, null);
    }

    private static PostBlock choice(String q, List<String> options, Integer correct) {
        return new PostBlock("choice", q, options, null, null, null, null, null, correct, "Porque sim");
    }

    @Test
    void aTextBlockIsTrimmedAndKept() {
        assertThat(PostBlocks.validate(List.of(text("  Olá turma  ")))).extracting(PostBlock::text).containsExactly("Olá turma");
    }

    @Test
    void noBlocksIsEmpty() {
        assertThat(PostBlocks.validate(null)).isEmpty();
        assertThat(PostBlocks.validate(List.of())).isEmpty();
    }

    @Test
    void anUnknownTypeIsRefused() {
        assertThatThrownBy(() -> PostBlocks.validate(List.of(new PostBlock("script", "x", null, null, null, null, null, null, null, null))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aBlockWithoutTextIsRefused() {
        assertThatThrownBy(() -> PostBlocks.validate(List.of(text("   ")))).isInstanceOf(ApiException.class);
    }

    @Test
    void anImageMustComeFromThePostStorage() {
        assertThat(PostBlocks.validate(List.of(image("/uploads/images/post/abc-123.png")))).hasSize(1);
        assertThatThrownBy(() -> PostBlocks.validate(List.of(image("https://exemplo.ao/pixel.png")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PostBlocks.validate(List.of(image("/uploads/images/avatar/abc.png")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PostBlocks.validate(List.of(image("/uploads/images/post/../x.png")))).isInstanceOf(ApiException.class);
    }

    @Test
    void atMostSixImagesAndTwentyBlocks() {
        List<PostBlock> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(image("/uploads/images/post/a" + i + ".png"));
        }
        assertThatThrownBy(() -> PostBlocks.validate(seven)).isInstanceOf(ApiException.class);
        List<PostBlock> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(text("linha " + i));
        }
        assertThatThrownBy(() -> PostBlocks.validate(many)).isInstanceOf(ApiException.class);
    }

    @Test
    void aChoiceNeedsTwoToFiveOptionsAndARightOne() {
        assertThat(PostBlocks.validate(List.of(choice("2+2?", List.of("3", "4"), 1)))).hasSize(1);
        assertThatThrownBy(() -> PostBlocks.validate(List.of(choice("2+2?", List.of("4"), 0)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PostBlocks.validate(List.of(choice("2+2?", List.of("3", "4"), 2)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PostBlocks.validate(List.of(choice("2+2?", List.of("3", "4"), null)))).isInstanceOf(ApiException.class);
    }

    @Test
    void aCalloutToneMustBeKnown() {
        assertThatThrownBy(() -> PostBlocks.validate(List.of(new PostBlock("callout", "Atenção", null, null, "rainbow", null, null, null, null, null))))
                .isInstanceOf(ApiException.class);
        assertThat(PostBlocks.validate(List.of(new PostBlock("callout", "Atenção", null, null, null, null, null, null, null, null))).get(0).tone())
                .isEqualTo("info");
    }

    @Test
    void theTotalTextIsCapped() {
        List<PostBlock> big = List.of(text("a".repeat(2000)), text("b".repeat(2000)), text("c"));
        assertThatThrownBy(() -> PostBlocks.validate(big)).isInstanceOf(ApiException.class);
    }

    @Test
    void theSummaryIsPlainTextAndSkipsImages() {
        List<PostBlock> blocks = PostBlocks.validate(List.of(
                new PostBlock("heading", "Juros simples", null, null, null, null, null, null, null, null),
                image("/uploads/images/post/a.png"),
                new PostBlock("list", null, List.of("capital", "taxa"), true, null, null, null, null, null, null)));
        assertThat(PostBlocks.summary(blocks)).isEqualTo("Juros simples\n- capital\n- taxa");
    }

    @Test
    void jsonRoundTripAndGarbage() {
        List<PostBlock> blocks = PostBlocks.validate(List.of(text("Olá turma")));
        assertThat(PostBlocks.fromJson(PostBlocks.toJson(blocks))).isEqualTo(blocks);
        assertThat(PostBlocks.fromJson("não é json")).isEmpty();
        assertThat(PostBlocks.fromJson(null)).isEmpty();
        assertThat(PostBlocks.toJson(List.of())).isNull();
    }
}

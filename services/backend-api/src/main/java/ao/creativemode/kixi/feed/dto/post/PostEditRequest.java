package ao.creativemode.kixi.feed.dto.post;

import java.util.List;

/**
 * What may change on a post (kixi#593): the title, the text or the built blocks.
 * The type, the school scope and the simulation figures never change — they are
 * decided by the coordinate and by the server, not by this request.
 *
 * <p>Every field is optional: an absent field keeps the value the post already has.</p>
 */
public record PostEditRequest(
        String title,
        String content,
        List<PostBlock> blocks
) {
    /** The content rule, the block rules and the type check live in the service, because they depend on the saved post's type. */
}

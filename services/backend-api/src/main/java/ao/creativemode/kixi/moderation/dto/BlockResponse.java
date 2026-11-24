package ao.creativemode.kixi.moderation.dto;

/**
 * The state of the block between the caller and another account. {@code blocked} is the
 * historical field (a block works both ways); {@code blockedByMe} tells the caller whether
 * they are the one who may lift it.
 */
public record BlockResponse(boolean blocked, boolean blockedByMe) {

    public BlockResponse(boolean blocked) {
        this(blocked, blocked);
    }
}

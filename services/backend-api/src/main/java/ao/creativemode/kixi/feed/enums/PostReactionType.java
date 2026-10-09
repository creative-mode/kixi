package ao.creativemode.kixi.feed.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum PostReactionType {
    APPLAUSE("applause"),
    STRENGTH("strength");

    private final String value;

    public static PostReactionType fromValue(String value){
        for(PostReactionType reaction : values()){
            if (reaction.value.equalsIgnoreCase(value)){
                return reaction;
            }
        }
        throw new IllegalArgumentException("Invalid post reaction " + value);
    }
}

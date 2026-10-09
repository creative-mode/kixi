package ao.creativemode.kixi.feed.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum PostType {
    EXAM("exam"),
    MILESTONE("milestone"),
    DOUBT("doubt"),
    TOPIC("topic");

    private final String value;

    public static PostType fromValue(String value) {
        for (PostType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid post type: " + value);
    }
}
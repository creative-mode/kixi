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

    /** Types a student or teacher may create by hand; EXAM and MILESTONE are system-generated. */
    public boolean isManual() {
        return this == DOUBT || this == TOPIC;
    }

    public static PostType fromValue(String value) {
        for (PostType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid post type: " + value);
    }
}

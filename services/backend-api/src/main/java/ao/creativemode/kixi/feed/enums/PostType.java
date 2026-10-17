package ao.creativemode.kixi.feed.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum PostType {
    EXAM("exam"),
    MILESTONE("milestone"),
    DOUBT("doubt"),
    TOPIC("topic"),
    SIMULATION("simulation"),
    STUDY_CARD("study_card"),
    SOLUTION("solution"),
    CHALLENGE("challenge");

    private final String value;

    /** Types a student or teacher may create by hand; EXAM and MILESTONE are system-generated. SIMULATION is the result card of an exam the author finished. */
    public boolean isManual() {
        return this == DOUBT || this == TOPIC || this == SIMULATION || usesBlocks();
    }

    /** Types that only make sense as a built post (issue #347): they need blocks. */
    public boolean usesBlocks() {
        return this == STUDY_CARD || this == SOLUTION || this == CHALLENGE;
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

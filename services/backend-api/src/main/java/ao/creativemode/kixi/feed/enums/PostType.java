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
    SIMULATION("simulation");

    private final String value;

    /** Types a student or teacher may create by hand; EXAM and MILESTONE are system-generated. SIMULATION is the result card of an exam the author finished. */
    public boolean isManual() {
        return this == DOUBT || this == TOPIC || this == SIMULATION;
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

package ao.creativemode.kixi.chat.model;

/**
 * What the student asked the tutor for (issue #115). {@link #GENERAL} is the tutor of
 * #114, unchanged: a session opened without a mode keeps exactly that behaviour.
 */
public enum TutorMode {
    /** Questions about the statement, as before the modes existed. */
    GENERAL,
    /** Walk through one question, with the student's answer when there is one. */
    EXPLAIN_QUESTION,
    /** Exercises like one question: real catalog questions first, new ones only after. */
    SIMILAR_EXERCISES,
    /** Doubts about a concept, explained from the statement's material. */
    CONCEPT;

    /** Whether the mode only makes sense around one question. */
    public boolean needsQuestion() {
        return this == EXPLAIN_QUESTION || this == SIMILAR_EXERCISES;
    }
}

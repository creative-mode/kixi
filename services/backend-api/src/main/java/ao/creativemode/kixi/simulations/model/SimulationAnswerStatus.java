package ao.creativemode.kixi.simulations.model;

/** How a simulation answer has been corrected. */
public enum SimulationAnswerStatus {
    PENDING_REVIEW,
    GRADED,
    AUTO_GRADED,
    /** Corrected by the AI in the SchoolOps flow (kixi#542): a final grade a person can only change by rectifying an appeal. */
    AI_GRADED
}

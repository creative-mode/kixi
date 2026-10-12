package ao.creativemode.kixi.simulations.community.dto;

/**
 * The opt-in behind {@code POST /api/v1/simulations/{id}/share}: the student
 * chooses to show one finished simulation, anonymously or under a name.
 *
 * <p>{@code anonymous} defaults to {@code true} when absent. A non-anonymous
 * share must name the {@code displayName} to show; the profile name is never
 * read automatically, so nothing leaks by omission.
 */
public record ShareSolutionRequest(Boolean anonymous, String displayName) { }

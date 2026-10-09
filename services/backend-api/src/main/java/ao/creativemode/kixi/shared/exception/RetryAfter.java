package ao.creativemode.kixi.shared.exception;

/**
 * Marks an {@link ApiException} whose response should carry a {@code Retry-After}
 * header with the number of seconds the client must wait.
 *
 * The global handler reads the delay through this interface, so a domain module
 * can rate-limit without the shared handler ever depending on that module —
 * {@code shared} must not import domain types (see ArchitectureTest).
 */
public interface RetryAfter {

    long getRetryAfterSeconds();
}

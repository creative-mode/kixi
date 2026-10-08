package ao.creativemode.kixi.chat.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Opens a tutor session bound to a statement the caller is allowed to read.
 * The statement is what scopes the tutor — without it there is nothing the
 * tutor may answer from (issue #114: restricted to official statements).
 */
public record ChatSessionRequest(
    @NotNull(message = "statementId is required")
    Long statementId
) {}

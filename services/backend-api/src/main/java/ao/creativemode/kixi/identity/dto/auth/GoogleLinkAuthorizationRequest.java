package ao.creativemode.kixi.identity.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleLinkAuthorizationRequest(
        @NotBlank @Size(max = 128) String password
) {}

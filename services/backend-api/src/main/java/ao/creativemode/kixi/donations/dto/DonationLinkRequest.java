package ao.creativemode.kixi.donations.dto;

import jakarta.validation.constraints.NotNull;

/** The statement an administrator made from the donation. */
public record DonationLinkRequest(@NotNull(message = "statementId is required") Long statementId) { }

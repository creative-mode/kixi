package ao.creativemode.kixi.donations.dto;

/** What the donor gets back: a reference to keep, never the id. */
public record DonationCreatedResponse(String reference) { }

package ao.creativemode.kixi.institutions.dto;

/**
 * The public shape of a verified institution (kixi#597): the list offered in the donation form.
 *
 * <p>Only the id, the human name and the sigla. Nothing else — not the code, not the logo,
 * not any date — because the list is served to anonymous callers and exists so the donation
 * form stops inventing variants of the same school name.</p>
 */
public record PublicInstitution(Long id, String name, String shortName) { }

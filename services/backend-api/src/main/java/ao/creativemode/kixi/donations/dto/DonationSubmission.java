package ao.creativemode.kixi.donations.dto;

import java.util.List;

/**
 * What the public form sent, before anything is trusted. The fields are as typed; the bytes are as
 * uploaded. {@code website} is the honeypot (a field no person sees) and {@code formStartedAt} is the
 * moment the form was opened, in epoch milliseconds, both read only to tell a script from a person.
 */
public record DonationSubmission(
        String donorName,
        String donorEmail,
        String institutionName,
        String subjectName,
        String classGrade,
        String schoolYear,
        String examType,
        String notes,
        boolean consent,
        String website,
        Long formStartedAt,
        List<Upload> files
) {

    /** One uploaded file, already read into memory (and already held to the size limits). */
    public record Upload(String filename, byte[] content) { }
}

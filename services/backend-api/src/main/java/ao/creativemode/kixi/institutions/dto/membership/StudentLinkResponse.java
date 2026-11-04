package ao.creativemode.kixi.institutions.dto.membership;

/** Um aluno de uma instituição; {@code processNumber} é o número de processo na escola (kixi#512), nulo se não houver. */
public record StudentLinkResponse(Long userId, Long accountId, String firstName, String lastName, String processNumber) {

    public StudentLinkResponse(Long userId, Long accountId, String firstName, String lastName) {
        this(userId, accountId, firstName, lastName, null);
    }
}

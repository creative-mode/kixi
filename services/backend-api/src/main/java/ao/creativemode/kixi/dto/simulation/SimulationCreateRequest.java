package ao.creativemode.kixi.dto.simulation;

public record SimulationCreateRequest(
        Long accountId,
        Long statementId,
        Long schoolYearId
) {
}

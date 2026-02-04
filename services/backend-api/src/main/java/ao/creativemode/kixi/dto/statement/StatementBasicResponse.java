package ao.creativemode.kixi.dto.statement;

public record StatementBasicResponse(
        Long id,
        String examType,
        String variant,
        String title,
        Integer durationMinutes,
        Integer totalMaxScore
) {
}

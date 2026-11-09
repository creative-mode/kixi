package ao.creativemode.kixi.training.dto;

import java.time.LocalDateTime;
import java.util.List;

/** O plano de treino de uma prova agendada: os temas que saem e o que há para treinar em cada um. */
public record TrainingPlanResponse(
        Long roomId,
        Long subjectId,
        String subjectName,
        String statementTitle,
        LocalDateTime startsAt,
        Integer durationMinutes,
        Double totalPoints,
        List<Item> items) {

    public record Item(
            Long topicId,
            String name,
            Integer questionCount,
            Double points,
            String cognitiveLevel,
            /** As questões respondíveis deste tema em enunciados de provas anteriores (nunca a prova da sala). */
            Integer available,
            List<Source> sources,
            /** Quantas dessas questões o aluno já respondeu, e quantas acertou, em simulações dele. */
            Integer attempted,
            Integer correct) {
    }

    public record Source(Long statementId, String title, Integer year, Integer count) {
    }
}

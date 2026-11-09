package ao.creativemode.kixi.training.dto;

import java.util.List;

public record MatrixResponse(Long roomId, String title, Double totalPoints, List<Item> items) {

    public record Item(
            Long id,
            Long topicId,
            String topicName,
            Integer questionCount,
            Double points,
            String cognitiveLevel,
            List<String> questionTypes) {
    }
}

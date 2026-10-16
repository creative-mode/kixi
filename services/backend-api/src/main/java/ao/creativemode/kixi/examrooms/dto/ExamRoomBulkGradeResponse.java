package ao.creativemode.kixi.examrooms.dto;

import java.util.List;

/** O que a correcção em massa fez: quantas notas gravou, quantas pela chave, e o que falhou (com a razão). */
public record ExamRoomBulkGradeResponse(int graded, int byKey, List<Failure> failed) {

    public record Failure(Long answerId, String message) { }
}

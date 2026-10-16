package ao.creativemode.kixi.examrooms.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * A correcção em massa de uma sala: as notas que o professor deu, e/ou `byKey` para dar a cotação inteira às
 * respostas que coincidem com a resposta modelo da questão.
 */
public record ExamRoomBulkGradeRequest(@Valid List<Grade> grades, boolean byKey) {

    public record Grade(@NotNull Long answerId, @NotNull @DecimalMin("0") Double score) { }
}

package ao.creativemode.kixi.examrooms.dto;

import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import java.time.LocalDateTime;
import java.util.List;

/** Student-facing question: alternatives are available, but never the answer key. */
public record ExamRoomQuestionResponse(
        Long id,
        Long statementId,
        Integer number,
        String text,
        String questionType,
        Double maxScore,
        Integer orderIndex,
        Integer pageIndex,
        List<QuestionOptionResponse> options,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** The group this question is an item of (an alínea); null for a top-level question. */
        Long parentId,
        /** The item's label inside its group. Show "{parent number}{label}", never {@code number}. */
        String label,
        /** The common statement of the group, shown above the item. */
        String parentText
) { }

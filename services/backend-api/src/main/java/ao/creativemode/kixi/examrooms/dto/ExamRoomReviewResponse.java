package ao.creativemode.kixi.examrooms.dto;

import java.util.List;

/**
 * As respostas abertas de uma sala, questão a questão, com a chave da prova ao lado: o que o professor precisa
 * para corrigir a sala toda de uma vez em vez de aluno a aluno.
 */
public record ExamRoomReviewResponse(List<Question> questions, int pending, int graded) {

    public record Question(Long questionId, Integer number, String label, Long parentId, String text,
            Double maxScore, String modelAnswer, List<Answer> answers) { }

    /** `status` é PENDING_REVIEW ou GRADED; `scoreObtained` só vem depois de corrigida. */
    public record Answer(Long answerId, Long accountId, String displayName, String answerText, String status,
            Float scoreObtained) { }
}

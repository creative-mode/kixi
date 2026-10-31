package ao.creativemode.kixi.examrooms.dto;

import java.util.List;

/**
 * As respostas abertas de uma sala, questão a questão, com a chave da prova ao lado: o que o professor precisa
 * para corrigir a sala toda de uma vez em vez de aluno a aluno.
 *
 * <p>Numa sala ligada a um plano do trimestre ({@code planLinked}) a correção é da IA, no fluxo do motor (kixi#542): as respostas
 * trazem a nota e a observação que ela deu, só para leitura, e as que ainda esperam a IA contam em {@code pending}.
 */
public record ExamRoomReviewResponse(List<Question> questions, int pending, int graded, int aiGraded, boolean planLinked) {

    public ExamRoomReviewResponse(List<Question> questions, int pending, int graded) {
        this(questions, pending, graded, 0, false);
    }

    public record Question(Long questionId, Integer number, String label, Long parentId, String text,
            Double maxScore, String modelAnswer, List<Answer> answers) { }

    /**
     * `status` é PENDING_REVIEW, GRADED ou AI_GRADED; `scoreObtained` só vem depois de corrigida. A observação, o nível e a
     * confiança só existem nas respostas corrigidas pela IA.
     */
    public record Answer(Long answerId, Long accountId, String displayName, String answerText, String status,
            Float scoreObtained, String aiFeedback, String aiLevel, Double aiConfidence) {

        public Answer(Long answerId, Long accountId, String displayName, String answerText, String status, Float scoreObtained) {
            this(answerId, accountId, displayName, answerText, status, scoreObtained, null, null, null);
        }
    }
}

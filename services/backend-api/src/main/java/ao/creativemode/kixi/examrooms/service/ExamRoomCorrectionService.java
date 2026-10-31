package ao.creativemode.kixi.examrooms.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import ao.creativemode.kixi.examrooms.dto.ExamRoomBulkGradeRequest;
import ao.creativemode.kixi.examrooms.dto.ExamRoomBulkGradeResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomGradeResponse;
import ao.creativemode.kixi.examrooms.dto.ExamRoomReviewResponse;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.service.SimulationSubmissionService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A correcção das respostas abertas de uma sala, em massa: as respostas por corrigir vêm agrupadas por questão,
 * com a resposta modelo (a chave) ao lado, e as notas gravam-se de uma vez. Cada nota passa pela mesma regra
 * de {@link SimulationSubmissionService#grade}: só o administrador ou o professor da turma e disciplina, só
 * respostas por corrigir, nunca acima da cotação.
 */
@Service
public class ExamRoomCorrectionService {
    private final ExamRoomMonitoringService monitoring;
    private final SimulationAnswerRepository answers;
    private final QuestionRepository questions;
    private final SimulationSubmissionService submission;

    /** Opcional: sem ele (testes unitários) não há provas de plano a proteger. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ao.creativemode.kixi.shared.service.PlanRoomLookup planRooms;

    public ExamRoomCorrectionService(ExamRoomMonitoringService monitoring, SimulationAnswerRepository answers,
            QuestionRepository questions, SimulationSubmissionService submission) {
        this.monitoring = monitoring;
        this.answers = answers;
        this.questions = questions;
        this.submission = submission;
    }

    /** As respostas abertas da sala (por corrigir e já corrigidas), questão a questão. */
    public Mono<ExamRoomReviewResponse> review(Long roomId, Long caller, boolean admin) {
        return monitoring.authorize(roomId, caller, admin).flatMap(room -> Mono.zip(
                monitoring.grades(roomId, caller, admin),
                questions.findAllByStatementIdAndDeletedAtIsNull(room.getStatementId()).collectList(),
                isPlanRoom(roomId))
            .flatMap(tuple -> {
                boolean planLinked = tuple.getT3();
                Map<Long, ExamRoomGradeResponse> bySimulation = tuple.getT1().stream()
                        .filter(grade -> grade.simulationId() != null && "FINISHED".equals(grade.status()))
                        .collect(Collectors.toMap(ExamRoomGradeResponse::simulationId, Function.identity(), (a, b) -> a));
                if (bySimulation.isEmpty()) {
                    return Mono.just(new ExamRoomReviewResponse(List.of(), 0, 0, 0, planLinked));
                }
                Map<Long, Question> byQuestion = tuple.getT2().stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity()));
                return answers.findAllBySimulationIdInAndDeletedAtIsNull(bySimulation.keySet()).collectList()
                        .map(rows -> build(rows, bySimulation, byQuestion, planLinked));
            }));
    }

    private Mono<Boolean> isPlanRoom(Long roomId) {
        return planRooms == null ? Mono.just(false) : planRooms.isPlanRoom(roomId);
    }

    private ExamRoomReviewResponse build(List<SimulationAnswer> rows, Map<Long, ExamRoomGradeResponse> bySimulation,
            Map<Long, Question> byQuestion, boolean planLinked) {
        Map<Long, List<ExamRoomReviewResponse.Answer>> perQuestion = new LinkedHashMap<>();
        int pending = 0;
        int graded = 0;
        int aiGraded = 0;
        for (SimulationAnswer row : rows) {
            SimulationAnswerStatus status = row.getReviewStatus();
            if (status != SimulationAnswerStatus.PENDING_REVIEW && status != SimulationAnswerStatus.GRADED
                    && status != SimulationAnswerStatus.AI_GRADED) continue;
            if (!byQuestion.containsKey(row.getQuestionId())) continue;
            ExamRoomGradeResponse who = bySimulation.get(row.getSimulationId());
            if (who == null) continue;
            if (status == SimulationAnswerStatus.PENDING_REVIEW) pending++;
            else if (status == SimulationAnswerStatus.AI_GRADED) aiGraded++;
            else graded++;
            perQuestion.computeIfAbsent(row.getQuestionId(), id -> new ArrayList<>())
                    .add(new ExamRoomReviewResponse.Answer(row.getId(), who.accountId(), who.displayName(),
                            row.getAnswerText(), status.name(), row.getScoreObtained(),
                            row.getAiFeedback(), row.getAiLevel(), row.getAiConfidence()));
        }
        List<ExamRoomReviewResponse.Question> out = perQuestion.entrySet().stream()
                .map(entry -> {
                    Question question = byQuestion.get(entry.getKey());
                    List<ExamRoomReviewResponse.Answer> list = entry.getValue().stream()
                            .sorted(Comparator.comparing(a -> a.displayName() == null ? "" : a.displayName()))
                            .toList();
                    return new ExamRoomReviewResponse.Question(question.getId(), question.getNumber(), question.getLabel(),
                            question.getParentId(), question.getText(), question.getMaxScore(),
                            question.getModelAnswer(), list);
                })
                .sorted(Comparator.comparing(q -> q.number() == null ? Integer.MAX_VALUE : q.number()))
                .toList();
        return new ExamRoomReviewResponse(out, pending, graded, aiGraded, planLinked);
    }

    /** Grava as notas dadas e, com `byKey`, a cotação inteira às respostas que coincidem com a chave. */
    public Mono<ExamRoomBulkGradeResponse> grade(Long roomId, Long caller, boolean admin, ExamRoomBulkGradeRequest request) {
        List<ExamRoomBulkGradeRequest.Grade> given = request.grades() == null ? List.of() : request.grades();
        // As provas de um plano do trimestre são corrigidas pela IA no fluxo do motor (kixi#542): o professor não as corrige à mão
        // na sala. O administrador, que também opera o motor, não é travado.
        return monitoring.authorize(roomId, caller, admin)
            .flatMap(room -> isPlanRoom(roomId).flatMap(planned -> planned && !admin
                ? Mono.<ao.creativemode.kixi.examrooms.model.ExamRoom>error(ApiException.conflict(
                    "As respostas das provas do plano do trimestre são corrigidas pela IA no fluxo; a nota só muda por reclamação"))
                : Mono.just(room)))
            .flatMap(room -> Mono.zip(
                monitoring.grades(roomId, caller, admin),
                questions.findAllByStatementIdAndDeletedAtIsNull(room.getStatementId()).collectList())
            .flatMap(tuple -> {
                Set<Long> simulationIds = tuple.getT1().stream().map(ExamRoomGradeResponse::simulationId)
                        .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                Map<Long, Question> byQuestion = tuple.getT2().stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity()));
                if (simulationIds.isEmpty()) {
                    return Mono.just(new ExamRoomBulkGradeResponse(0, 0, List.of()));
                }
                return answers.findAllBySimulationIdInAndDeletedAtIsNull(simulationIds).collectList()
                    .flatMap(rows -> apply(rows, byQuestion, given, request.byKey(), caller, admin));
            }));
    }

    private Mono<ExamRoomBulkGradeResponse> apply(List<SimulationAnswer> rows, Map<Long, Question> byQuestion,
            List<ExamRoomBulkGradeRequest.Grade> given, boolean byKey, Long caller, boolean admin) {
        Map<Long, SimulationAnswer> inRoom = rows.stream()
                .collect(Collectors.toMap(SimulationAnswer::getId, Function.identity()));
        List<ExamRoomBulkGradeResponse.Failure> failed = new ArrayList<>();
        // (resposta, nota, pela chave?) — cada resposta uma só vez; a nota dada à mão vence a da chave.
        Map<Long, double[]> plan = new LinkedHashMap<>();
        for (ExamRoomBulkGradeRequest.Grade grade : given) {
            if (!inRoom.containsKey(grade.answerId())) {
                failed.add(new ExamRoomBulkGradeResponse.Failure(grade.answerId(), "A resposta não é desta sala."));
                continue;
            }
            plan.put(grade.answerId(), new double[] {grade.score(), 0});
        }
        if (byKey) {
            Set<Long> taken = new HashSet<>(plan.keySet());
            for (SimulationAnswer row : rows) {
                if (taken.contains(row.getId()) || row.getReviewStatus() != SimulationAnswerStatus.PENDING_REVIEW) continue;
                Question question = byQuestion.get(row.getQuestionId());
                if (question == null || question.getMaxScore() == null) continue;
                if (matchesKey(row.getAnswerText(), question.getModelAnswer())) {
                    plan.put(row.getId(), new double[] {question.getMaxScore(), 1});
                }
            }
        }
        int[] counts = {0, 0};
        return Flux.fromIterable(plan.entrySet())
                .concatMap(entry -> submission.grade(entry.getKey(), entry.getValue()[0], caller, admin)
                        .doOnSuccess(saved -> {
                            counts[0]++;
                            if (entry.getValue()[1] == 1) counts[1]++;
                        })
                        .onErrorResume(error -> {
                            failed.add(new ExamRoomBulkGradeResponse.Failure(entry.getKey(),
                                    error instanceof ApiException ? error.getMessage() : "Não foi possível gravar a nota."));
                            return Mono.empty();
                        }))
                .then(Mono.fromSupplier(() -> new ExamRoomBulkGradeResponse(counts[0], counts[1], failed)));
    }

    /** Igual à chave sem contar maiúsculas, acentos, pontuação nem espaços a mais. Em branco nunca coincide. */
    static boolean matchesKey(String answer, String key) {
        String a = normalize(answer);
        return !a.isEmpty() && a.equals(normalize(key));
    }

    static String normalize(String value) {
        if (value == null) return "";
        String plain = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}

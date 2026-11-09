package ao.creativemode.kixi.training.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionImage;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionImageRepository;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.service.SimulationService;
import ao.creativemode.kixi.training.dto.MatrixPracticeRequest;
import ao.creativemode.kixi.training.dto.MatrixPracticeResponse;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * «Treino por temas» (kixi#729): com os temas e as quantidades que o aluno escolheu, monta uma simulação com questões de
 * provas anteriores.
 *
 * <p>O treino vive num <b>enunciado virtual</b>, privado do aluno ({@code statements.practice_owner_account_id}), para quem
 * as questões de vários enunciados são «um enunciado» e tudo o resto (respostas, correção, resultado, tutor) funciona sem
 * alterações. As questões escolhidas são copiadas para lá, com as opções, as imagens (a mesma referência, sem duplicar o
 * ficheiro) e a chave de resposta; cada cópia guarda a questão de origem para o plano saber o que o aluno já treinou.
 * Questões de grupo copiam o cabeçalho e só as alíneas escolhidas.</p>
 */
@Service
public class MatrixPracticeService {

    static final String SOURCE = "practice";
    /** Questões pedidas num só treino, somadas todos os temas. */
    static final int MAX_QUESTIONS = 100;
    /** Treinos em curso por conta e matriz. */
    static final int MAX_OPEN_PRACTICES = 20;

    private final TrainingPlanService plans;
    private final TrainingPoolReader pool;
    private final StatementRepository statements;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final QuestionImageRepository images;
    private final SimulationService simulations;
    private final Random random;

    @Autowired
    public MatrixPracticeService(TrainingPlanService plans, TrainingPoolReader pool, StatementRepository statements,
            QuestionRepository questions, QuestionOptionRepository options, QuestionImageRepository images,
            SimulationService simulations) {
        this(plans, pool, statements, questions, options, images, simulations, new Random());
    }

    MatrixPracticeService(TrainingPlanService plans, TrainingPoolReader pool, StatementRepository statements,
            QuestionRepository questions, QuestionOptionRepository options, QuestionImageRepository images,
            SimulationService simulations, Random random) {
        this.plans = plans;
        this.pool = pool;
        this.statements = statements;
        this.questions = questions;
        this.options = options;
        this.images = images;
        this.simulations = simulations;
        this.random = random;
    }

    @Transactional
    public Mono<MatrixPracticeResponse> start(Long accountId, MatrixPracticeRequest request) {
        Set<Long> requestedTopics = new LinkedHashSet<>();
        for (MatrixPracticeRequest.Item item : request.items()) {
            if (!requestedTopics.add(item.topicId())) {
                return Mono.error(ApiException.badRequest("O tema " + item.topicId() + " aparece repetido no pedido."));
            }
        }
        int requestedTotal = request.items().stream().mapToInt(MatrixPracticeRequest.Item::count).sum();
        if (requestedTotal > MAX_QUESTIONS) {
            return Mono.error(ApiException.badRequest("Um treino tem no máximo " + MAX_QUESTIONS
                    + " questões no total (pediu " + requestedTotal + ")."));
        }
        return plans.context(request.roomId(), accountId, requestedTopics).flatMap(ctx -> pool
                .openPractices(accountId, ctx.room().roomId()).defaultIfEmpty(0L).flatMap(open -> open >= MAX_OPEN_PRACTICES
                        ? Mono.<MatrixPracticeResponse>error(ApiException.conflict(
                                "Termina ou cancela um treino antes de começar outro."))
                        : build(accountId, request, ctx, requestedTopics)));
    }

    private Mono<MatrixPracticeResponse> build(Long accountId, MatrixPracticeRequest request, TrainingPlanService.Context ctx,
            Set<Long> requestedTopics) {
        {
            Set<Long> inMatrix = ctx.matrix().items().stream().map(MatrixResponse.Item::topicId).collect(Collectors.toSet());
            for (Long topic : requestedTopics) {
                if (!inMatrix.contains(topic)) {
                    return Mono.<MatrixPracticeResponse>error(ApiException.badRequest(
                            "O tema " + topic + " não faz parte da matriz desta prova."));
                }
            }
            Map<Long, List<PracticeAllocator.Candidate>> candidates = new HashMap<>();
            for (TrainingPoolReader.PoolRow row : ctx.pool()) {
                candidates.computeIfAbsent(row.topicId(), key -> new ArrayList<>()).add(new PracticeAllocator.Candidate(
                        row.questionId(), row.statementId(), row.year(), row.attempted(), row.correct()));
            }
            List<PracticeAllocator.Request> asked = request.items().stream()
                    .map(item -> new PracticeAllocator.Request(item.topicId(), item.count())).toList();
            List<PracticeAllocator.Allocation> allocation = PracticeAllocator.allocate(asked, candidates, random);
            List<Long> chosen = allocation.stream().flatMap(a -> a.questionIds().stream()).toList();
            if (chosen.isEmpty()) {
                return Mono.<MatrixPracticeResponse>error(ApiException.badRequest(
                        "Não há questões disponíveis para os temas escolhidos."));
            }
            int matrixTotal = ctx.matrix().items().stream().mapToInt(MatrixResponse.Item::questionCount).sum();
            String name = ctx.matrix().title() != null ? ctx.matrix().title() : ctx.room().statementTitle();
            String title = "Treino · " + (name == null ? "prova" : name);
            Integer duration = request.durationMinutes() != null ? request.durationMinutes()
                    : proportionalDuration(ctx.room().durationMinutes(), chosen.size(), matrixTotal);
            return virtualStatement(accountId, ctx.room().statementId(), ctx.room().roomId(), title, duration, chosen)
                    .flatMap(statement -> simulations.createForPractice(accountId, statement.getId(), shorten(title, 200)))
                    .map(simulation -> new MatrixPracticeResponse(simulation, allocation.stream()
                            .map(a -> new MatrixPracticeResponse.Allocation(a.topicId(), a.requested(), a.provided()))
                            .toList()));
        }
    }

    /** Sem duração pedida: a da prova, na proporção das questões do treino face às da matriz (e nunca mais do que ela). */
    static Integer proportionalDuration(Integer roomMinutes, int chosen, int matrixTotal) {
        if (roomMinutes == null || roomMinutes <= 0) {
            return null;
        }
        if (matrixTotal <= 0) {
            return roomMinutes;
        }
        long minutes = Math.round(roomMinutes * (double) chosen / matrixTotal);
        return (int) Math.max(1, Math.min(roomMinutes, minutes));
    }

    private Mono<Statement> virtualStatement(Long accountId, Long roomStatementId, Long roomId, String title,
            Integer duration, List<Long> chosen) {
        return statements.findAnyByIdAndDeletedAtIsNull(roomStatementId)
                .switchIfEmpty(Mono.error(() -> ApiException.notFound("Enunciado da sala não encontrado.")))
                .flatMap(origin -> loadSources(chosen).flatMap(sources -> {
                    List<PracticeClonePlan.Step> steps = PracticeClonePlan.build(chosen, sources);
                    Statement statement = new Statement();
                    statement.setTitle(shorten(title, 500));
                    statement.setExamType("Treino");
                    statement.setDurationMinutes(duration);
                    statement.setSubjectId(origin.getSubjectId());
                    statement.setClassId(origin.getClassId());
                    statement.setCourseId(origin.getCourseId());
                    statement.setTermId(origin.getTermId());
                    statement.setSchoolYearId(origin.getSchoolYearId());
                    statement.setInstitutionId(origin.getInstitutionId());
                    statement.setCreatedBy(accountId);
                    statement.setPracticeOwnerAccountId(accountId);
                    statement.setPracticeExamRoomId(roomId);
                    statement.setVisible(false);
                    statement.setNeedsReview(false);
                    statement.setSource(SOURCE);
                    double total = steps.stream().map(PracticeClonePlan.Step::source).filter(Question::isAnswerable)
                            .map(Question::getMaxScore).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum();
                    statement.setTotalMaxScore(total > 0 ? total : null);
                    return statements.save(statement).flatMap(saved -> copy(saved.getId(), steps).thenReturn(saved));
                }));
    }

    /** As questões de origem e os cabeçalhos dos seus grupos, por id. */
    private Mono<Map<Long, Question>> loadSources(List<Long> chosen) {
        return questions.findAllById(chosen).collectMap(Question::getId).flatMap(found -> {
            Set<Long> parentIds = found.values().stream().map(Question::getParentId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
            parentIds.removeAll(found.keySet());
            if (parentIds.isEmpty()) {
                return Mono.just(found);
            }
            return questions.findAllById(parentIds).collectMap(Question::getId).map(parents -> {
                Map<Long, Question> all = new HashMap<>(found);
                parents.values().stream().filter(parent -> parent.getDeletedAt() == null)
                        .forEach(parent -> all.put(parent.getId(), parent));
                return all;
            });
        });
    }

    private Mono<Void> copy(Long statementId, List<PracticeClonePlan.Step> steps) {
        Map<Long, Long> cloneOf = new HashMap<>();
        return Flux.fromIterable(steps).concatMap(step -> {
            Question source = step.source();
            Question clone = new Question();
            clone.setStatementId(statementId);
            clone.setNumber(step.number());
            clone.setOrderIndex(step.orderIndex());
            clone.setText(source.getText());
            clone.setRawText(source.getRawText());
            clone.setOcrFormulas(source.getOcrFormulas());
            clone.setOcrReviewed(source.getOcrReviewed());
            clone.setQuestionType(source.getQuestionType());
            clone.setGroupLayout(source.getGroupLayout());
            clone.setMaxScore(source.getMaxScore());
            clone.setOcrConfidence(source.getOcrConfidence());
            clone.setPageIndex(source.getPageIndex());
            clone.setNeedsReview(false);
            clone.setModelAnswer(source.getModelAnswer());
            clone.setSourceQuestionId(source.getId());
            clone.setLabel(step.label());
            clone.setParentId(step.parentSourceId() == null ? null : cloneOf.get(step.parentSourceId()));
            return questions.save(clone).flatMap(saved -> {
                cloneOf.put(source.getId(), saved.getId());
                return copyOptions(source.getId(), saved.getId()).then(copyImages(source.getId(), saved.getId()));
            });
        }).then();
    }

    private Mono<Void> copyOptions(Long sourceId, Long cloneId) {
        return options.findAllByQuestionIdAndDeletedAtIsNull(sourceId).concatMap(option -> {
            QuestionOption copy = new QuestionOption(cloneId, option.getOptionLabel(), option.getOptionText(), option.getIsCorrect());
            copy.setOrderIndex(option.getOrderIndex());
            copy.setOcrConfidence(option.getOcrConfidence());
            return options.save(copy);
        }).then();
    }

    /** As imagens apontam para o mesmo ficheiro (mesma {@code storage_key}): não se duplica o binário. */
    private Mono<Void> copyImages(Long sourceId, Long cloneId) {
        return images.findByQuestionIdAndDeletedAtIsNullOrderByOrderIndexAsc(sourceId).concatMap(image -> {
            QuestionImage copy = new QuestionImage();
            copy.setQuestionId(cloneId);
            copy.setImageUrl(image.getImageUrl());
            copy.setStorageKey(image.getStorageKey());
            copy.setCaption(image.getCaption());
            copy.setOcrKind(image.getOcrKind());
            copy.setOcrPageIndex(image.getOcrPageIndex());
            copy.setOcrScore(image.getOcrScore());
            copy.setOrderIndex(image.getOrderIndex());
            return images.save(copy);
        }).then();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}

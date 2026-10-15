package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.dto.statement.ManualStatementRequest;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Creates statements written in the exam builder. Unlike the OCR flows, the
 * statement is tied to an institution and one of its subjects, and a teacher
 * can only build for an institution they are affiliated with.
 */
@Service
public class ManualStatementService {

    static final String SOURCE = "manual";

    private final StatementRepository statementRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final InstitutionAccessService accessService;
    private final StatementLinkValidationService validator;

    public ManualStatementService(
        StatementRepository statementRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        InstitutionAccessService accessService,
        StatementLinkValidationService validator
    ) {
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.accessService = accessService;
        this.validator = validator;
    }

    @Transactional
    public Mono<Statement> create(ManualStatementRequest request, Long accountId, boolean admin) {
        return Mono.<Void>fromRunnable(() -> requireAValidShape(request))
            .then(Mono.defer(() -> validator.requireAClassForTeachers(request.classId(), admin)))
            .then(Mono.defer(() -> accessService.requireCanAuthor(
                accountId, admin, request.institutionId(), request.subjectId(), request.classId())))
            .then(Mono.defer(() -> validator.validate(
                request.institutionId(),
                request.schoolYearId(),
                request.termId(),
                request.classId(),
                request.subjectId(),
                request.courseId())))
            .then(Mono.defer(() -> statementRepository.save(toStatement(request, accountId))))
            .flatMap(statement ->
                Flux.defer(() -> {
                    // The order of the paper, flattened: a group, then its items, then the next
                    // question. `number` cannot say it (3 < 4 < 301), so order_index does.
                    java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();
                    return Flux.fromIterable(indexed(request.questions()))
                        .concatMap(entry -> saveTopLevel(statement.getId(), entry.number(), entry.question(), position));
                })
                    .then(Mono.just(statement))
            );
    }

    static final String OPEN = "open";
    static final String MULTIPLE_CHOICE = "multiple_choice";
    static final String TRUE_FALSE = "true_false";
    private static final java.util.Set<String> LEAF_TYPES = java.util.Set.of(OPEN, MULTIPLE_CHOICE, TRUE_FALSE);
    private static final int MAX_ITEMS = 99;
    private static final List<ManualStatementRequest.Option> TRUE_FALSE_OPTIONS = List.of(
        new ManualStatementRequest.Option("A", "Verdadeiro", false),
        new ManualStatementRequest.Option("B", "Falso", false));

    /** A question of the paper: a leaf, or a group whose items are saved right after it. */
    private Mono<Question> saveTopLevel(Long statementId, int number, ManualStatementRequest.Question data,
            java.util.concurrent.atomic.AtomicInteger position) {
        if (!isGroup(data)) {
            return saveLeaf(statementId, number, data.text(), leafType(data.type(), data.options()),
                data.maxScore(), data.options(), null, null, position.incrementAndGet());
        }
        // The group is only the common statement: nothing to answer, so no score and no options.
        Question header = new Question(statementId, number, data.text().trim(), Question.GROUP);
        header.setOrderIndex(position.incrementAndGet());
        return questionRepository.save(header).flatMap(saved -> {
            List<ManualStatementRequest.Item> items = data.items();
            return Flux.range(0, items.size())
                .concatMap(k -> {
                    ManualStatementRequest.Item item = items.get(k);
                    // Items are numbered under their group (301, 302, ...): the number only has to be
                    // unique per statement and leave the printed numbers of the top level alone; the
                    // order of the paper is order_index.
                    return saveLeaf(statementId, number * 100 + k + 1, item.text(),
                        leafType(item.type(), item.options()), item.maxScore(), item.options(),
                        saved.getId(), labelOf(item.label(), k), position.incrementAndGet());
                })
                .then(Mono.just(saved));
        });
    }

    private Mono<Question> saveLeaf(Long statementId, int number, String text, String type, Double maxScore,
            List<ManualStatementRequest.Option> given, Long parentId, String label, int orderIndex) {
        List<ManualStatementRequest.Option> options = optionsFor(type, given);

        Question question = new Question(statementId, number, text.trim(), type);
        question.setOrderIndex(orderIndex);
        question.setMaxScore(maxScore);
        question.setParentId(parentId);
        question.setLabel(label);

        return questionRepository
            .save(question)
            .flatMap(saved ->
                Flux.fromIterable(withOrder(options))
                    .concatMap(option -> {
                        QuestionOption entity = new QuestionOption(
                            saved.getId(),
                            option.option().label().trim(),
                            option.option().text().trim(),
                            Boolean.TRUE.equals(option.option().correct())
                        );
                        entity.setOrderIndex(option.order());
                        return optionRepository.save(entity);
                    })
                    .then(Mono.just(saved))
            );
    }

    private static boolean isGroup(ManualStatementRequest.Question data) {
        return Question.GROUP.equals(normalized(data.type())) || (data.items() != null && !data.items().isEmpty());
    }

    private static String normalized(String type) {
        return type == null || type.isBlank() ? null : type.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The type a leaf is stored with: the one asked for, or the old derivation (options mean multiple choice). */
    private static String leafType(String requested, List<ManualStatementRequest.Option> options) {
        String type = normalized(requested);
        if (type != null) {
            return type;
        }
        return options == null || options.isEmpty() ? OPEN : MULTIPLE_CHOICE;
    }

    /** True/false always has its two options; the teacher only marks which one is right. */
    private static List<ManualStatementRequest.Option> optionsFor(String type, List<ManualStatementRequest.Option> given) {
        boolean none = given == null || given.isEmpty();
        if (TRUE_FALSE.equals(type) && none) {
            return TRUE_FALSE_OPTIONS;
        }
        return none ? List.of() : given;
    }

    private static String labelOf(String given, int index) {
        if (given != null && !given.isBlank()) {
            return given.trim().toLowerCase(java.util.Locale.ROOT);
        }
        // a..z, then a1, b1... for the rare group with more than 26 items.
        char letter = (char) ('a' + index % 26);
        return index < 26 ? String.valueOf(letter) : letter + String.valueOf(index / 26);
    }

    /**
     * The shape of the paper, before anything is written: a bad one is a 400 that names the
     * question, not a half-saved statement.
     */
    private static void requireAValidShape(ManualStatementRequest request) {
        for (int i = 0; i < request.questions().size(); i++) {
            ManualStatementRequest.Question question = request.questions().get(i);
            String where = "question " + (i + 1);
            String type = normalized(question.type());
            boolean hasItems = question.items() != null && !question.items().isEmpty();
            if (type != null && !LEAF_TYPES.contains(type) && !Question.GROUP.equals(type)) {
                throw ApiException.badRequest("Unknown question type '" + question.type() + "' in " + where);
            }
            if (hasItems && type != null && !Question.GROUP.equals(type)) {
                throw ApiException.badRequest("Only a group question can have items (" + where + ")");
            }
            if (Question.GROUP.equals(type) && !hasItems) {
                throw ApiException.badRequest("A group question needs at least one item (" + where + ")");
            }
            if (isGroup(question)) {
                if (question.options() != null && !question.options().isEmpty()) {
                    throw ApiException.badRequest("A group question has no options of its own (" + where + ")");
                }
                requireValidItems(question.items(), where);
            } else {
                requireValidLeaf(question.type(), question.options(), where);
            }
        }
    }

    private static void requireValidItems(List<ManualStatementRequest.Item> items, String where) {
        if (items.size() > MAX_ITEMS) {
            throw ApiException.badRequest("A group can have at most " + MAX_ITEMS + " items (" + where + ")");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int k = 0; k < items.size(); k++) {
            ManualStatementRequest.Item item = items.get(k);
            String itemWhere = where + ", item " + labelOf(item.label(), k);
            if (Question.GROUP.equals(normalized(item.type()))) {
                throw ApiException.badRequest("An item cannot be a group (" + itemWhere + ")");
            }
            if (!seen.add(labelOf(item.label(), k))) {
                throw ApiException.badRequest("Two items of the same group share a label (" + itemWhere + ")");
            }
            requireValidLeaf(item.type(), item.options(), itemWhere);
        }
    }

    private static void requireValidLeaf(String requested, List<ManualStatementRequest.Option> options, String where) {
        String type = normalized(requested);
        int count = options == null ? 0 : options.size();
        if (type == null) {
            return; // derived: options mean multiple choice, none mean open
        }
        if (OPEN.equals(type) && count > 0) {
            throw ApiException.badRequest("An open question has no options (" + where + ")");
        }
        if (MULTIPLE_CHOICE.equals(type) && count == 0) {
            throw ApiException.badRequest("A multiple-choice question needs options (" + where + ")");
        }
        if (TRUE_FALSE.equals(type) && count != 0 && count != 2) {
            throw ApiException.badRequest("A true/false question has exactly two options (" + where + ")");
        }
    }

    private Statement toStatement(ManualStatementRequest request, Long accountId) {
        Statement statement = new Statement();
        statement.setTitle(request.title().trim());
        statement.setExamType(request.examType().trim());
        statement.setDurationMinutes(request.durationMinutes());
        statement.setVariant(blankToNull(request.variant()));
        statement.setInstructions(blankToNull(request.instructions()));
        statement.setTotalMaxScore(totalScore(request));
        statement.setSchoolYearId(request.schoolYearId());
        statement.setTermId(request.termId());
        statement.setSubjectId(request.subjectId());
        statement.setClassId(request.classId());
        statement.setCourseId(request.courseId());
        statement.setInstitutionId(request.institutionId());
        statement.setCreatedBy(accountId);
        statement.setVisible(Boolean.TRUE.equals(request.visible()));
        statement.setNeedsReview(false);
        statement.setSource(SOURCE);
        statement.setDeletedAt(null);
        return statement;
    }

    /** The sum of what can be scored: the leaves. A group is only a statement, its items carry the score. */
    private Double totalScore(ManualStatementRequest request) {
        return request.questions().stream()
            .flatMap(question -> isGroup(question)
                ? question.items().stream().map(ManualStatementRequest.Item::maxScore)
                : java.util.stream.Stream.of(question.maxScore()))
            .filter(score -> score != null)
            .mapToDouble(Double::doubleValue)
            .sum();
    }

    /** Shared with the edit path so a blank reads the same on both routes. */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record NumberedQuestion(int number, ManualStatementRequest.Question question) {}

    private record OrderedOption(int order, ManualStatementRequest.Option option) {}

    private List<NumberedQuestion> indexed(List<ManualStatementRequest.Question> questions) {
        return java.util.stream.IntStream.range(0, questions.size())
            .mapToObj(i -> new NumberedQuestion(i + 1, questions.get(i)))
            .toList();
    }

    private List<OrderedOption> withOrder(List<ManualStatementRequest.Option> options) {
        return java.util.stream.IntStream.range(0, options.size())
            .mapToObj(i -> new OrderedOption(i, options.get(i)))
            .toList();
    }
}

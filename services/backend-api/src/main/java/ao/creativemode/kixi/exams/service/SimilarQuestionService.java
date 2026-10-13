package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.MeService;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Real catalog questions like the one a student is working on (issue #115): the
 * tutor offers these before it makes up new exercises.
 *
 * <p>Candidates come from other published statements of the same subject
 * <b>and of the caller's own school</b>, reviewed and not deleted (ADR-0010: only
 * validated data reaches the AI layer). They are ranked by how many words they share
 * with the question in focus, and questions of the same type win a tie. Nothing
 * smarter than that: the point is to point at real papers, and a shared word count is
 * enough to beat "the most recent ones".</p>
 *
 * <p>The school is the caller's, resolved exactly as
 * {@code StatementCatalogService.affiliationOf} resolves it for the catalog page —
 * subjects are shared across the platform, so a subject filter alone reaches other
 * schools. An account with no affiliation gets nothing rather than everything: the
 * student then gets new exercises invented by the tutor, which is a worse answer and
 * not a leak.</p>
 *
 * <p>The answer key never leaves here: the options carry label and text only.</p>
 */
@Service
public class SimilarQuestionService {

    private static final Logger log = LoggerFactory.getLogger(SimilarQuestionService.class);

    /** How many candidates are read before ranking. */
    static final int CANDIDATES = 200;

    /** Words shorter than this carry no topic ("de", "que", "uma"...). */
    private static final int MIN_WORD = 4;

    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final StatementRepository statements;
    private final MeService meService;

    public SimilarQuestionService(QuestionRepository questions, QuestionOptionRepository options,
            StatementRepository statements, MeService meService) {
        this.questions = questions;
        this.options = options;
        this.statements = statements;
        this.meService = meService;
    }

    /**
     * The school the account studies in, or empty when it has none or cannot be read.
     *
     * <p>Empty means the search does not happen. It is the same rule the catalog page
     * follows for a student who is not enrolled anywhere: no affiliation, no school to
     * stay inside, and widening the search is the one option that is not available.</p>
     *
     * <p>A profile that cannot be read is empty too, not an error: this service reads
     * the caller's affiliation to bound its own search, and failing to do so is not the
     * student's problem to solve. The tutor then invents exercises, which is the same
     * answer an account with no school gets.</p>
     *
     * <p>The failure is logged before it is swallowed. Fail-closed is the right
     * behaviour, but silent: if {@code MeService} starts failing for everyone, the
     * symptom is "the tutor stopped suggesting similar exercises", which points nowhere
     * near the actual cause. The warning is what turns that into a traceable fault
     * instead of a mystery (issue #218).</p>
     */
    private Mono<Long> schoolOf(Long accountId) {
        return meService.getMe(accountId)
                .flatMap(me -> me.school() == null ? Mono.empty() : Mono.just(me.school().id()))
                .doOnError(error -> log.warn(
                        "Could not read the affiliation of account {} for similar exercises; "
                                + "treating as no school",
                        accountId, error))
                .onErrorReturn(0L)
                .filter(schoolId -> schoolId != null && schoolId != 0L);
    }

    /** A catalog question as the tutor may show it. */
    public record SimilarQuestion(Long questionId, Integer number, String text, String questionType,
            Long statementId, String statementTitle, List<Choice> choices) { }

    /** One option, without {@code isCorrect}. */
    public record Choice(String label, String text) { }

    public Mono<List<SimilarQuestion>> similarTo(
            Long accountId, Statement statement, Question focus, int limit) {
        if (statement == null || focus == null || statement.getSubjectId() == null || limit <= 0) {
            return Mono.just(List.of());
        }
        Set<String> focusWords = words(focus.getText());
        return schoolOf(accountId)
            .flatMapMany(institutionId -> questions.findCatalogCandidates(
                    statement.getSubjectId(), institutionId, statement.getId(), CANDIDATES))
            .collectList()
            .flatMap(candidates -> {
                List<Question> ranked = candidates.stream()
                    .filter(candidate -> !Objects.equals(candidate.getId(), focus.getId()))
                    .sorted(Comparator
                        .comparingLong((Question candidate) -> shared(focusWords, candidate)).reversed()
                        .thenComparing(candidate -> sameType(focus, candidate) ? 0 : 1))
                    .limit(limit)
                    .toList();
                if (ranked.isEmpty()) {
                    return Mono.just(List.<SimilarQuestion>of());
                }
                List<Long> questionIds = ranked.stream().map(Question::getId).toList();
                List<Long> statementIds = ranked.stream().map(Question::getStatementId).distinct().toList();
                return Mono.zip(
                        options.findAllByQuestionIdInAndDeletedAtIsNull(questionIds)
                            .collect(Collectors.groupingBy(QuestionOption::getQuestionId)),
                        // The same gate findCatalogCandidates already applied to the
                        // questions, applied again here: a plain findAllById would be a
                        // second, unguarded read, and a statement unpublished or deleted
                        // in the gap between the two queries would still arrive with a
                        // title in the response (issue #218).
                        statements.findAllByIdInAndVisibleTrueAndDeletedAtIsNullAndNeedsReviewFalse(statementIds)
                            .collectMap(Statement::getId, Function.identity()))
                    .map(tuple -> ranked.stream()
                        .map(question -> toSimilar(question,
                            tuple.getT2().get(question.getStatementId()),
                            tuple.getT1().getOrDefault(question.getId(), List.of())))
                        .toList());
            });
    }

    private static SimilarQuestion toSimilar(Question question, Statement statement, List<QuestionOption> rows) {
        List<Choice> choices = rows.stream()
            .sorted(Comparator
                .comparing(QuestionOption::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(option -> option.getOptionLabel() == null ? "" : option.getOptionLabel()))
            .map(option -> new Choice(option.getOptionLabel(), option.getOptionText()))
            .toList();
        return new SimilarQuestion(question.getId(), question.getNumber(), question.getText(),
            question.getQuestionType(), question.getStatementId(),
            statement == null ? null : statement.getTitle(), choices);
    }

    private static long shared(Set<String> focusWords, Question candidate) {
        if (focusWords.isEmpty()) return 0;
        return words(candidate.getText()).stream().filter(focusWords::contains).count();
    }

    private static boolean sameType(Question focus, Question candidate) {
        return focus.getQuestionType() != null
            && focus.getQuestionType().equalsIgnoreCase(String.valueOf(candidate.getQuestionType()));
    }

    /** Lower case, no accents, words of {@link #MIN_WORD} letters or more. */
    static Set<String> words(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
        return Arrays.stream(plain.split("[^\\p{L}\\p{N}]+"))
            .filter(word -> word.length() >= MIN_WORD)
            .collect(Collectors.toSet());
    }
}

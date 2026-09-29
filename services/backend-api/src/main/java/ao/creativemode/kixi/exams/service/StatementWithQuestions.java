package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import java.util.List;

/**
 * A statement together with its questions and options, as returned by both
 * the regular lookup methods and the OCR-based creation flows.
 */
public record StatementWithQuestions(
    Statement statement,
    List<Question> questions,
    List<QuestionOption> options
) {}

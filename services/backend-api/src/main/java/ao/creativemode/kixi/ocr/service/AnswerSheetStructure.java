package ao.creativemode.kixi.ocr.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.List;

/**
 * A estrutura da prova tal como o OCR das folhas a precisa: uma linha por questão que recebe resposta, com o número
 * que o aluno escreve, o tipo e, nas de escolha múltipla, as letras das opções. As questões-mãe (tipo {@code group})
 * não têm resposta própria e ficam de fora; as suas subquestões entram com o número que têm. Vem do enunciado do
 * Kixi, não do pedido: quem chama o OCR não escolhe o que se procura.
 */
public final class AnswerSheetStructure {

    private AnswerSheetStructure() {
    }

    public static ObjectNode of(StatementWithQuestions statement, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("statementId", statement.statement().getId());
        ArrayNode questions = root.putArray("questions");
        statement.questions().stream()
                .filter(question -> question.getNumber() != null && !"group".equalsIgnoreCase(question.getQuestionType()))
                .sorted(Comparator.comparing(Question::getNumber))
                .forEach(question -> {
                    ObjectNode node = questions.addObject();
                    node.put("id", String.valueOf(question.getId()));
                    node.put("number", question.getNumber());
                    node.put("type", question.getQuestionType() == null ? "open" : question.getQuestionType().toLowerCase());
                    List<String> labels = statement.options().stream()
                            .filter(option -> question.getId().equals(option.getQuestionId()))
                            .map(QuestionOption::getOptionLabel)
                            .filter(label -> label != null && !label.isBlank())
                            .sorted()
                            .toList();
                    if (!labels.isEmpty() && "multiple_choice".equalsIgnoreCase(question.getQuestionType())) {
                        ArrayNode options = node.putArray("options");
                        labels.forEach(options::add);
                    }
                });
        return root;
    }
}

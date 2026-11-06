package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.simulations.model.Simulation;

import java.util.List;
import java.util.Set;

/** Que questões de um enunciado contam numa simulação (kixi#729): todas, ou só as de um treino montado. */
final class QuestionSelection {

    private QuestionSelection() {}

    /** As questões a responder e corrigir: as respondíveis, restringidas à seleção quando existe. */
    static List<Question> answerable(Simulation simulation, List<Question> all) {
        Set<Long> chosen = simulation.selectedQuestionIdSet();
        return all.stream()
                .filter(Question::isAnswerable)
                .filter(question -> chosen == null || chosen.contains(question.getId()))
                .toList();
    }

    /** Para mostrar: as escolhidas e os cabeçalhos de grupo de que dependem (o enunciado comum). */
    static List<Question> visible(Simulation simulation, List<Question> all) {
        Set<Long> chosen = simulation.selectedQuestionIdSet();
        if (chosen == null) {
            return all;
        }
        Set<Long> parents = all.stream()
                .filter(question -> chosen.contains(question.getId()) && question.getParentId() != null)
                .map(Question::getParentId)
                .collect(java.util.stream.Collectors.toSet());
        return all.stream()
                .filter(question -> chosen.contains(question.getId()) || parents.contains(question.getId()))
                .toList();
    }
}

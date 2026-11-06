package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/**
 * Uma porta entre o feed e a gamificação (kixi#729): quando o autor de uma pergunta aceita uma resposta, quem
 * respondeu pode ganhar a medalha «Mentor da turma». O feed só conhece esta interface.
 */
public interface AnswerAcceptedListener {

    /** @param totalAccepted quantas respostas suas, ao todo, já foram aceites */
    Mono<Void> answerAccepted(Long answererAccountId, long totalAccepted);
}

package ao.creativemode.kixi.simulations.model;

/**
 * Em que ponto está a correção das respostas abertas de uma simulação já entregue (kixi#609). Derivado das respostas, nunca
 * guardado, para não se desatualizar: o ecrã de resultado, o histórico e o cartão do feed leem o mesmo estado.
 */
public enum CorrectionStatus {
    /** Há respostas abertas e nenhuma foi corrigida ainda. */
    PENDING,
    /** Já se corrigiu alguma resposta aberta, mas há outras por corrigir. */
    IN_PROGRESS,
    /** Nenhuma resposta aberta por corrigir (também numa simulação só com objetivas). A nota é a final. */
    DONE,
    /** O motor avisou que não conseguiu corrigir: há respostas por corrigir e a correção não vai sozinha. */
    FAILED
}

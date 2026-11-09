package ao.creativemode.kixi.training.dto;

/** {@code tagged}: as questões etiquetadas agora; {@code untagged}: as que continuam sem tema. */
public record AutoTagResponse(int tagged, int untagged) {
}

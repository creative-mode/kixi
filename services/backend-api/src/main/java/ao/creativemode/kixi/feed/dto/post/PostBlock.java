package ao.creativemode.kixi.feed.dto.post;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Um bloco de um post construído (issue #347). Um só registo com campos opcionais, em vez de uma
 * hierarquia: cada {@code type} usa só os campos que lhe dizem respeito, e
 * {@link ao.creativemode.kixi.feed.service.PostBlocks} recusa o resto.
 *
 * <ul>
 *   <li>{@code heading}, {@code text}, {@code formula}: {@code text}</li>
 *   <li>{@code list}: {@code items} e, opcionalmente, {@code ordered}</li>
 *   <li>{@code callout}: {@code text} e, opcionalmente, {@code tone} (info, tip ou warning)</li>
 *   <li>{@code image}: {@code url} do próprio armazenamento, {@code alt} e {@code caption}</li>
 *   <li>{@code choice}: a pergunta em {@code text}, as opções em {@code items}, a certa em
 *       {@code correct} (posição a partir de 0) e a {@code explanation}</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostBlock(
        String type,
        String text,
        List<String> items,
        Boolean ordered,
        String tone,
        String url,
        String alt,
        String caption,
        Integer correct,
        String explanation
) { }

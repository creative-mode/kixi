package ao.creativemode.kixi.shared.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import ao.creativemode.kixi.shared.dto.ProblemDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * O carácter NUL (U+0000) não cabe em nenhuma coluna de texto do PostgreSQL: chegava à base de dados e voltava como um
 * 500 (vaga 1A, P1-2). Recusa-se na fronteira, para todas as rotas de uma vez, com o 400 do formato de erro padrão:
 * <ul>
 *   <li>nos corpos JSON, qualquer texto (também o de um {@code Map<String, Object>}) com NUL falha a leitura;</li>
 *   <li>nos parâmetros de pesquisa e de caminho, o mesmo (um {@code ?query=%00}).</li>
 * </ul>
 * Os limites de tamanho continuam a ser os das colunas, em cada pedido (Bean Validation).
 */
@Configuration
public class InputValidationConfig {

    static final String MESSAGE = "Text must not contain the NUL (U+0000) character";

    @Bean
    Module noNulStringsModule() {
        SimpleModule module = new SimpleModule("no-nul-strings");
        module.addDeserializer(String.class, new NulRejectingStringDeserializer());
        return module;
    }

    /** Os textos de um corpo JSON. */
    static final class NulRejectingStringDeserializer extends StringDeserializer {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = super.deserialize(parser, context);
            if (value != null && value.indexOf('\u0000') >= 0) {
                return (String) context.handleWeirdStringValue(String.class, "<NUL>", MESSAGE);
            }
            return value;
        }
    }

    /**
     * Os parâmetros de pesquisa e de caminho de todas as rotas: um {@code %00} no endereço (no caminho ou na pesquisa, também
     * descodificado) é recusado antes de chegar a um controlador, com o mesmo corpo de erro (RFC 9457) dos outros 400.
     */
    @Bean
    @Order(0)
    WebFilter nulInAddressFilter(ObjectMapper mapper) {
        return (exchange, chain) -> {
            var request = exchange.getRequest();
            String raw = (request.getURI().getRawPath() + "?" + request.getURI().getRawQuery()).toLowerCase(Locale.ROOT);
            boolean nul = raw.contains("%00");
            if (!nul) {
                nul = request.getQueryParams().entrySet().stream().anyMatch(entry -> entry.getKey().indexOf('\u0000') >= 0
                        || entry.getValue().stream().anyMatch(value -> value != null && value.indexOf('\u0000') >= 0));
            }
            if (!nul) {
                return chain.filter(exchange);
            }
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST.value(), MESSAGE)
                    .withTitle(HttpStatus.BAD_REQUEST.getReasonPhrase()).withInstance(exchange);
            try {
                DataBuffer buffer = response.bufferFactory().wrap(mapper.writeValueAsBytes(problem));
                return response.writeWith(Mono.just(buffer));
            } catch (JsonProcessingException e) {
                return response.setComplete();
            }
        };
    }
}

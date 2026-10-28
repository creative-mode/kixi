package ao.creativemode.kixi.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class LegacyApiDeprecationFilterTest {

    private final LegacyApiDeprecationFilter filter = new LegacyApiDeprecationFilter();

    private MockServerWebExchange run(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        filter.filter(exchange, ex -> Mono.empty()).block();
        return exchange;
    }

    @Test
    void aLegacyRouteSaysItIsGoingAway() {
        var headers = run("/api/simulations/12").getResponse().getHeaders();

        assertThat(headers.getFirst("Deprecation")).isEqualTo("true");
        assertThat(headers.getFirst("Sunset")).isEqualTo(LegacyApiDeprecationFilter.SUNSET);
        assertThat(headers.getFirst("Link")).isEqualTo("</api/v1/simulations/12>; rel=\"successor-version\"");
    }

    @Test
    void theVersionedRoutesAreUntouched() {
        assertThat(run("/api/v1/simulations").getResponse().getHeaders().getFirst("Deprecation")).isNull();
        assertThat(run("/api/simulationsx").getResponse().getHeaders().getFirst("Deprecation")).isNull();
    }
}

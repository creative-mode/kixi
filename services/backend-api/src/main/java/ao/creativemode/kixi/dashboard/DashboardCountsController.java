package ao.creativemode.kixi.dashboard;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code GET /api/v1/analytics/counts}: the record counts of the manager's dashboard in one request (issue #416),
 * next to {@code /analytics/overview}. Staff only ({@code SecurityConfig}, the {@code /api/v1/analytics/**} rule); what
 * each role gets is decided by {@link DashboardCountsService}.
 *
 * <p>It lives in its own module because counting what the lists return needs the services behind those lists, and
 * {@code analytics} may only depend on {@code shared} ({@code ArchitectureTest}).</p>
 */
@RestController
public class DashboardCountsController {

    private final DashboardCountsService service;

    public DashboardCountsController(DashboardCountsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/analytics/counts")
    public Mono<ResponseEntity<Map<String, Long>>> counts() {
        return Mono.defer(service::counts).map(ResponseEntity::ok);
    }
}

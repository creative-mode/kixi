package ao.creativemode.kixi.register;

import ao.creativemode.kixi.exams.service.StatementReadAccessService;
import ao.creativemode.kixi.register.TermSheetReadService.Detail;
import ao.creativemode.kixi.register.TermSheetReadService.Mine;
import ao.creativemode.kixi.register.TermSheetReadService.Reader;
import ao.creativemode.kixi.register.TermSheetReadService.Summary;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Leitura das pautas pelo professor e pelo aluno (kixi#506). A rota já deixa o administrador e o professor entrar em
 * {@code /term-sheets} e só o aluno em {@code /me/term-sheets}; o que cada um vê decide-se no serviço.
 */
@RestController
@RequestMapping("/api/v1")
public class TermSheetController {

    private final TermSheetReadService service;
    private final StatementReadAccessService readAccess;
    private final CurrentAccountService currentAccount;

    public TermSheetController(TermSheetReadService service, StatementReadAccessService readAccess, CurrentAccountService currentAccount) {
        this.service = service;
        this.readAccess = readAccess;
        this.currentAccount = currentAccount;
    }

    @GetMapping("/term-sheets")
    public Flux<Summary> list(@RequestParam(required = false) Long classId, @RequestParam(required = false) Integer termNumber,
            @RequestParam(required = false) Long subjectId) {
        return reader().flatMapMany(reader -> service.list(reader, classId, termNumber, subjectId));
    }

    @GetMapping("/term-sheets/{sheetId}")
    public Mono<Detail> detail(@PathVariable String sheetId) {
        return reader().flatMap(reader -> service.detail(reader, sheetId));
    }

    @GetMapping("/me/term-sheets")
    public Flux<Mine> mine() {
        return currentAccount.requiredAccountId().flatMapMany(service::mine);
    }

    /** Administrador: tudo; professor: o que as suas atribuições cobrem; mais ninguém. */
    private Mono<Reader> reader() {
        return currentAccount.hasAnyRole("ADMIN").flatMap(admin -> {
            if (admin) return Mono.just(new Reader(null));
            return currentAccount.hasAnyRole("TEACHER").flatMap(teacher -> {
                if (!teacher) return Mono.error(ApiException.forbidden("Term sheets are only available to staff"));
                return currentAccount.requiredAccountId().flatMap(readAccess::teacherScope).map(Reader::new);
            });
        });
    }
}

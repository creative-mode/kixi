package ao.creativemode.kixi.institutions.controller;

import ao.creativemode.kixi.institutions.dto.friendship.ColleagueResponse;
import ao.creativemode.kixi.institutions.dto.friendship.FriendRequest;
import ao.creativemode.kixi.institutions.dto.friendship.FriendResponse;
import ao.creativemode.kixi.institutions.service.FriendshipService;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Following colleagues, and what they agreed to (issue #121).
 *
 * <p>Every path takes the account from the security chain and never from the body or
 * the query string. A friendship is the one relationship here where the argument
 * matters: a request naming its own sender is a request anybody could send.</p>
 */
@RestController
@RequestMapping("/api/v1/friends")
public class FriendshipController {

    private final FriendshipService service;
    private final CurrentAccountService current;
    private final ApiAbuseRateLimiter abuseRateLimiter;

    public FriendshipController(FriendshipService service, CurrentAccountService current,
            ApiAbuseRateLimiter abuseRateLimiter) {
        this.service = service;
        this.current = current;
        this.abuseRateLimiter = abuseRateLimiter;
    }

    /** Who the caller may ask, from their own school year. Rate limited with the profile searches (#406). */
    @GetMapping("/search")
    public Flux<ColleagueResponse> search(@RequestParam(required = false) String q) {
        return current.requiredAccountId()
                .flatMapMany(accountId -> abuseRateLimiter.checkSearch(accountId)
                        .thenMany(Flux.defer(() -> service.searchColleagues(accountId, q))));
    }

    /** The inbox: requests waiting for an answer. */
    @GetMapping("/requests")
    public Flux<FriendResponse> requests() {
        return current.requiredAccountId()
                .flatMapMany(service::listRequests);
    }

    /** The people whose marks {@code GET /leaderboard?scope=friends} ranks. */
    @GetMapping
    public Flux<FriendResponse> friends() {
        return current.requiredAccountId()
                .flatMapMany(service::listFriends);
    }

    /** Ask to be someone's friend. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FriendResponse> request(@RequestBody FriendRequest body) {
        return current.requiredAccountId()
                .flatMap(accountId -> service.request(accountId, body));
    }

    /** Answer a request addressed to the caller. */
    @PostMapping("/{id}/accept")
    public Mono<FriendResponse> accept(@PathVariable Long id) {
        return current.requiredAccountId()
                .flatMap(accountId -> service.accept(accountId, id));
    }

    /** Remove the friendship, or withdraw a request. Either side may do it. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> remove(@PathVariable Long id) {
        return current.requiredAccountId()
                .flatMap(accountId -> service.remove(accountId, id));
    }
}
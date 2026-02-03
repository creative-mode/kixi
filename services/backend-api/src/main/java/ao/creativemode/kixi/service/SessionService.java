package ao.creativemode.kixi.service;

import ao.creativemode.kixi.model.Session;
import ao.creativemode.kixi.model.Account;
import ao.creativemode.kixi.repository.SessionRepository;
import ao.creativemode.kixi.repository.AccountRepository;
import ao.creativemode.kixi.dto.sessions.SessionResponse;
import ao.creativemode.kixi.dto.sessions.SessionRequest;
import ao.creativemode.kixi.common.exception.ApiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class SessionService {

    private static final long DEFAULT_SESSION_EXPIRY_HOURS = 24;

    private final SessionRepository repository;
    private final AccountRepository accountRepository;

    public SessionService(SessionRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    public Flux<SessionResponse> findAllActive() {
        return repository.findAllByDeletedAtIsNull()
                .flatMap(this::loadAccountRelationship)
                .map(this::toResponse);
    }

    public Flux<SessionResponse> findAllDeleted() {
        return repository.findAllByDeletedAtIsNotNull()
                .flatMap(this::loadAccountRelationship)
                .map(this::toResponse);
    }

    public Mono<SessionResponse> findByIdActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Session not found")))
                .flatMap(this::loadAccountRelationship)
                .map(this::toResponse);
    }

    public Mono<SessionResponse> create(SessionRequest dto) {
        String normalizedToken = dto.token() != null ? dto.token().trim() : null;
        String normalizedIpAddress = dto.ipAddress() != null ? dto.ipAddress().trim() : null;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = dto.expiresAt() != null
                ? dto.expiresAt()
                : now.plus(DEFAULT_SESSION_EXPIRY_HOURS, ChronoUnit.HOURS);

        Session entity = Session.builder()
                .accountId(dto.accountId())
                .token(normalizedToken)
                .ipAddress(normalizedIpAddress)
                .expiresAt(expiresAt)
                .lastUsed(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return repository.save(entity)
                .map(this::toResponse);
    }

    public Mono<SessionResponse> update(Long id, SessionRequest dto) {
        String normalizedToken = dto.token() != null ? dto.token().trim() : null;
        String normalizedIpAddress = dto.ipAddress() != null ? dto.ipAddress().trim() : null;

        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Session not found")))
                .flatMap(entity -> {
                    entity.setAccountId(dto.accountId());
                    entity.setToken(normalizedToken);
                    entity.setIpAddress(normalizedIpAddress);
                    if (dto.expiresAt() != null) {
                        entity.setExpiresAt(dto.expiresAt());
                    }
                    entity.setUpdatedAt(LocalDateTime.now());

                    return repository.save(entity);
                })
                .map(this::toResponse);
    }

    public Mono<Void> softDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Session not found")))
                .flatMap(entity -> {
                    entity.setDeletedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> restore(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Session is not deleted")))
                .flatMap(entity -> {
                    entity.setDeletedAt(null);
                    return repository.save(entity);
                })
                .then();
    }

    public Mono<Void> hardDelete(Long id) {
        return repository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(
                        Mono.error(ApiException.badRequest("Only deleted sessions can be permanently removed")))
                .flatMap(repository::delete)
                .then();
    }

    /**
     * Loads the Account relationship for a Session entity.
     * This is necessary in R2DBC since it doesn't support lazy loading like JPA.
     */
    private Mono<Session> loadAccountRelationship(Session session) {
        if (session.getAccountId() == null) {
            return Mono.just(session);
        }

        return accountRepository.findByIdAndDeletedAtIsNull(session.getAccountId())
                .doOnNext(session::setAccount)
                .thenReturn(session)
                .defaultIfEmpty(session);
    }

    private SessionResponse toResponse(Session entity) {
        return new SessionResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getToken(),
                entity.getIpAddress(),
                entity.getExpiresAt(),
                entity.getLastUsed(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}

package ao.creativemode.kixi.service;

import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.users.UserRequest;
import ao.creativemode.kixi.dto.users.UserResponse;
import ao.creativemode.kixi.model.User;
import ao.creativemode.kixi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository repository;

    @InjectMocks
    private UserService service;

    private User userEntity;
    private UserRequest userRequest;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        userEntity = new User(1L, "João", "Silva");
        userEntity.setId(1L);
        userEntity.setPhoto("https://example.com/photo.jpg");
        userEntity.setCreatedAt(LocalDateTime.now());
        userEntity.setUpdatedAt(LocalDateTime.now());
        userEntity.setDeletedAt(null);

        userRequest = new UserRequest(1L, "João", "Silva", "https://example.com/photo.jpg");
    }

    @Test
    void testFindAllActive() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(userEntity));

        StepVerifier.create(service.findAllActive())
                .expectNextMatches(response -> response.id().equals(1L) && response.firstName().equals("João"))
                .verifyComplete();

        verify(repository, times(1)).findAllByDeletedAtIsNull();
    }

    @Test
    void testFindAllDeleted() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(userEntity));

        StepVerifier.create(service.findAllDeleted())
                .expectNextMatches(response -> response.id().equals(1L))
                .verifyComplete();

        verify(repository, times(1)).findAllByDeletedAtIsNotNull();
    }

    @Test
    void testFindByIdActive_Success() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(userEntity));

        StepVerifier.create(service.findByIdActive(1L))
                .expectNextMatches(response -> response.id().equals(1L) && response.accountId().equals(1L))
                .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(1L);
    }

    @Test
    void testFindByIdActive_NotFound() {
        when(repository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(999L))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(999L);
    }

    @Test
    void testFindByAccountIdActive() {
        when(repository.findByAccountIdAndDeletedAtIsNull(1L)).thenReturn(Flux.just(userEntity));

        StepVerifier.create(service.findByAccountIdActive(1L))
                .expectNextMatches(response -> response.accountId().equals(1L))
                .verifyComplete();

        verify(repository, times(1)).findByAccountIdAndDeletedAtIsNull(1L);
    }

    @Test
    void testFindByAccountIdActive_InvalidId() {
        StepVerifier.create(service.findByAccountIdActive(-1L))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, never()).findByAccountIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    void testCreate_Success() {
        when(repository.save(any(User.class))).thenReturn(Mono.just(userEntity));

        StepVerifier.create(service.create(userRequest))
                .expectNextMatches(response -> response.firstName().equals("João"))
                .verifyComplete();

        verify(repository, times(1)).save(any(User.class));
    }

    @Test
    void testCreate_InvalidAccountId() {
        UserRequest invalidRequest = new UserRequest(-1L, "João", "Silva", null);

        StepVerifier.create(service.create(invalidRequest))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void testCreate_MissingFirstName() {
        UserRequest invalidRequest = new UserRequest(1L, "", "Silva", null);

        StepVerifier.create(service.create(invalidRequest))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, never()).save(any(User.class));
    }

    @Test
    void testUpdate_Success() {
        User updatedEntity = new User(2L, "Maria", "Santos");
        updatedEntity.setId(1L);
        updatedEntity.setCreatedAt(LocalDateTime.now());
        updatedEntity.setUpdatedAt(LocalDateTime.now());

        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(userEntity));
        when(repository.save(any(User.class))).thenReturn(Mono.just(updatedEntity));

        UserRequest updateRequest = new UserRequest(2L, "Maria", "Santos", null);

        StepVerifier.create(service.update(1L, updateRequest))
                .expectNextMatches(response -> response.firstName().equals("Maria"))
                .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(1L);
        verify(repository, times(1)).save(any(User.class));
    }

    @Test
    void testUpdate_NotFound() {
        when(repository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(999L, userRequest))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(999L);
        verify(repository, never()).save(any(User.class));
    }

    @Test
    void testUpdate_InvalidId() {
        StepVerifier.create(service.update(-1L, userRequest))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, never()).findByIdAndDeletedAtIsNull(anyLong());
        verify(repository, never()).save(any(User.class));
    }

    @Test
    void testSoftDelete_Success() {
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(userEntity));
        when(repository.save(any(User.class))).thenReturn(Mono.just(userEntity));

        StepVerifier.create(service.softDelete(1L))
                .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(1L);
        verify(repository, times(1)).save(any(User.class));
    }

    @Test
    void testSoftDelete_NotFound() {
        when(repository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(999L))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNull(999L);
        verify(repository, never()).save(any(User.class));
    }

    @Test
    void testRestore_Success() {
        userEntity.markAsDeleted();

        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(userEntity));
        when(repository.save(any(User.class))).thenReturn(Mono.just(userEntity));

        StepVerifier.create(service.restore(1L))
                .verifyComplete();

        verify(repository, times(1)).findByIdAndDeletedAtIsNotNull(1L);
        verify(repository, times(1)).save(any(User.class));
    }

    @Test
    void testRestore_NotDeleted() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorMatches(e -> e instanceof ApiException)
                .verify();

        verify(repository, times(1)).findByIdAndDeletedAtIsNotNull(1L);
        verify(repository, never()).save(any(User.class));
    }
}

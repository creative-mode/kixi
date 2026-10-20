package ao.creativemode.kixi.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.shared.service.DisplayNameReader.Brief;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

class MaskedDisplayNamesTest {

    private final UserRepository users = mock(UserRepository.class);
    private final MaskedDisplayNames names = new MaskedDisplayNames(users);

    private User user(long accountId, String first, String last, String photo) {
        User u = new User();
        u.setAccountId(accountId);
        u.setFirstName(first);
        u.setLastName(last);
        u.setPhoto(photo);
        return u;
    }

    @Test
    void manyAuthorsAreReadWithOneQueryAndComeWithNameAndPhoto() {
        when(users.findByAccountIdInAndDeletedAtIsNull(anyCollection())).thenReturn(Flux.just(
                user(1, "Ana", "Neves", "/uploads/images/avatar/a.png"),
                user(2, "Bruno", "", null)));

        Map<Long, Brief> briefs = names.briefsOf(List.of(1L, 2L, 3L)).block();

        assertThat(briefs).containsOnlyKeys(1L, 2L);
        assertThat(briefs.get(1L)).isEqualTo(new Brief("Ana N.", "/uploads/images/avatar/a.png"));
        assertThat(briefs.get(2L)).isEqualTo(new Brief("Bruno", null));
    }

    @Test
    void noAuthorsMeansNoQuery() {
        assertThat(names.briefsOf(List.of()).block()).isEmpty();
        verify(users, never()).findByAccountIdInAndDeletedAtIsNull(anyCollection());
    }
}

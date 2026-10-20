package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.shared.service.DisplayNameReader;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * {@link DisplayNameReader} over the users of this module.
 *
 * <p>The masking happens here and not at the call site: the initial is the half that
 * must not leak, and a rule that lives next to the name is a rule that cannot be
 * forgotten by the next caller.</p>
 */
@Service
public class MaskedDisplayNames implements DisplayNameReader {

    private final UserRepository users;

    public MaskedDisplayNames(UserRepository users) {
        this.users = users;
    }

    @Override
    public Mono<String> maskedNameOf(Long accountId) {
        return users.findByAccountIdAndDeletedAtIsNull(accountId)
                .next()
                .map(MaskedDisplayNames::mask)
                .filter(name -> !name.isEmpty());
    }

    @Override
    public Mono<Map<Long, Brief>> briefsOf(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return users.findByAccountIdInAndDeletedAtIsNull(accountIds)
                .collect(HashMap<Long, Brief>::new, (map, user) -> {
                    String name = mask(user);
                    map.putIfAbsent(user.getAccountId(), new Brief(name.isEmpty() ? null : name, user.getPhoto()));
                })
                .map(map -> (Map<Long, Brief>) map);
    }

    private static String mask(ao.creativemode.kixi.identity.model.User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String initial = last.isEmpty()
                ? "" : last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
        return (first + " " + initial).trim();
    }
}

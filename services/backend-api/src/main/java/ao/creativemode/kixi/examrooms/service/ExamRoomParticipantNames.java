package ao.creativemode.kixi.examrooms.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import reactor.core.publisher.Mono;

/**
 * The name the teacher reads for an exam-room participant (#595).
 *
 * <p>Live progress, grades and integrity all need the same answer, so the rule
 * lives here once: the profile name and, without one, the username. The account
 * id is never a name — an account that no longer exists gets a neutral label
 * instead of leaking its number into the screen.
 */
@Component
public class ExamRoomParticipantNames {
    /** Rótulo neutro para uma conta que já não existe (ou nunca existiu). */
    static final String UNKNOWN = "Aluno sem conta";

    private final AccountRepository accounts;
    private final UserRepository users;

    public ExamRoomParticipantNames(AccountRepository accounts, UserRepository users) {
        this.accounts = accounts;
        this.users = users;
    }

    public Mono<Map<Long, String>> resolve(List<ExamRoomParticipant> rows) {
        List<Long> ids = rows.stream().map(ExamRoomParticipant::getAccountId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Mono.just(Map.of());
        return Mono.zip(accounts.findAllByIdInAndDeletedAtIsNull(ids).collectList(),
                users.findByAccountIdInAndDeletedAtIsNull(ids).collectList())
                .map(tuple -> {
                    Map<Long, String> usernames = tuple.getT1().stream()
                            .filter(account -> account.getId() != null && account.getUsername() != null)
                            .collect(Collectors.toMap(Account::getId, Account::getUsername, (left, right) -> left));
                    Map<Long, User> profiles = tuple.getT2().stream()
                            .filter(user -> user.getAccountId() != null)
                            .collect(Collectors.toMap(User::getAccountId, Function.identity(), (left, right) -> left));
                    return ids.stream().collect(Collectors.toMap(Function.identity(),
                            id -> nameOf(profiles.get(id), usernames.get(id))));
                });
    }

    private static String nameOf(User profile, String username) {
        if (profile != null) {
            String name = Stream.of(profile.getFirstName(), profile.getLastName())
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining(" "));
            if (!name.isBlank()) return name;
        }
        return username == null || username.isBlank() ? UNKNOWN : username;
    }
}

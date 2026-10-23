package ao.creativemode.kixi.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Signs a short-lived OAuth state cookie that binds the callback to an authenticated account. */
@Component
public class GoogleLinkStateService {

    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final String DOMAIN = "kixi-google-link-v1";

    private final byte[] key;
    private final Clock clock;

    @Autowired
    public GoogleLinkStateService(@Value("${app.jwt.secret}") String jwtSecret) {
        this(jwtSecret, Clock.systemUTC());
    }

    GoogleLinkStateService(String jwtSecret, Clock clock) {
        this.key = (DOMAIN + ":" + jwtSecret).getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public IssuedState issue(Long accountId) {
        String nonce = UUID.randomUUID().toString();
        long expiresAt = clock.instant().plus(LIFETIME).getEpochSecond();
        String payload = payload(accountId, expiresAt, nonce);
        return new IssuedState(nonce, payload + "~" + sign(payload));
    }

    public Optional<Long> accountForCallback(String state, String cookie) {
        if (state == null || cookie == null) {
            return Optional.empty();
        }
        String[] fields = cookie.split("~", -1);
        if (fields.length != 5 || !"v1".equals(fields[0]) || !state.equals(fields[3])) {
            return Optional.empty();
        }
        try {
            long accountId = Long.parseLong(fields[1]);
            long expiresAt = Long.parseLong(fields[2]);
            long now = clock.instant().getEpochSecond();
            if (accountId <= 0 || expiresAt <= now || expiresAt > now + LIFETIME.toSeconds()) {
                return Optional.empty();
            }
            String payload = payload(accountId, expiresAt, state);
            return MessageDigest.isEqual(
                    sign(payload).getBytes(StandardCharsets.US_ASCII),
                    fields[4].getBytes(StandardCharsets.US_ASCII))
                    ? Optional.of(accountId)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String payload(long accountId, long expiresAt, String nonce) {
        return "v1~" + accountId + "~" + expiresAt + "~" + nonce;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("OAuth state signing is unavailable", exception);
        }
    }

    public record IssuedState(String state, String cookieValue) {}
}

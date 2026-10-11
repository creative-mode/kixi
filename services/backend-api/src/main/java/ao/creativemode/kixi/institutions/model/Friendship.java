package ao.creativemode.kixi.institutions.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A request between two accounts, and then, once accepted, the friendship itself
 * (issue #121).
 *
 * <p>The pair is stored the same way round for everyone — {@code accountLow} is always
 * the smaller id — so a friendship is one row and not two, and "are A and B friends"
 * is one lookup instead of a search over both orderings.</p>
 */
@Table("friendships")
public class Friendship {

    /** Not yet agreed. Only the addressee sees it. */
    public static final String PENDING = "PENDING";

    /** Both sides see each other. This is what the ranking counts. */
    public static final String ACCEPTED = "ACCEPTED";

    @Id
    private Long id;

    private Long accountLow;
    private Long accountHigh;
    private Long requestedBy;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public Friendship() { }

    /** A new request between {@code a} and {@code b}, sent by {@code requester}. */
    public static Friendship between(Long a, Long b, Long requester) {
        Friendship friendship = new Friendship();
        friendship.accountLow = Math.min(a, b);
        friendship.accountHigh = Math.max(a, b);
        friendship.requestedBy = requester;
        friendship.status = PENDING;
        return friendship;
    }

    /** The other account of the pair, seen from one of them. */
    public Long otherThan(Long accountId) {
        return accountLow.equals(accountId) ? accountHigh : accountLow;
    }

    public boolean involves(Long accountId) {
        return accountLow.equals(accountId) || accountHigh.equals(accountId);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountLow() {
        return accountLow;
    }

    public void setAccountLow(Long accountLow) {
        this.accountLow = accountLow;
    }

    public Long getAccountHigh() {
        return accountHigh;
    }

    public void setAccountHigh(Long accountHigh) {
        this.accountHigh = accountHigh;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(Long requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void restore() {
        deletedAt = null;
        updatedAt = LocalDateTime.now();
    }

    public void accept() {
        status = ACCEPTED;
        updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        deletedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
}
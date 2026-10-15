package ao.creativemode.kixi.donations.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** One photo or PDF of a donation, kept in the object storage under a random key. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("statement_donation_files")
public class DonationFile {

    @Id
    private Long id;

    @Column("donation_id")
    private Long donationId;

    /** Random: the original name never reaches the storage path. */
    @Column("storage_key")
    private String storageKey;

    @Column("original_name")
    private String originalName;

    /** What the bytes are (from the magic bytes), not what the client claimed. */
    @Column("content_type")
    private String contentType;

    @Column("size_bytes")
    private Long sizeBytes;

    @Column("sha256")
    private String sha256;

    @Column("page_count")
    private Integer pageCount;

    @Column("created_at")
    private LocalDateTime createdAt;
}

package ao.creativemode.kixi.feed.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("post_reports")
public class PostReport {

    @Id
    private Long id;

    @Column("post_id")
    private Long postId;

    @Column("reporter_account_id")
    private Long reporterAccountId;

    @Column("reason")
    private String reason;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
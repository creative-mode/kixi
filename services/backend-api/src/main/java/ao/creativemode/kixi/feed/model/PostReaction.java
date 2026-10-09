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
@Table("post_reactions")
public class PostReaction {

    @Id
    private Long id;

    @Column("post_id")
    private Long postId;

    @Column("account_id")
    private Long accountId;

    @Column("type")
    private String type; // 'applause', 'strength'

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
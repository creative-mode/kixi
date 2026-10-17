package ao.creativemode.kixi.institutions.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Who may see a person's full profile (issue #336). Read and written by custom queries only. */
@Table("user_profile_settings")
@Getter
@Setter
@NoArgsConstructor
public class ProfileSetting {

    public static final String PUBLIC = "public";
    public static final String SCHOOL = "school";
    public static final String PRIVATE = "private";

    @Id
    @Column("user_id")
    private Long userId;

    @Column("visibility")
    private String visibility;
}

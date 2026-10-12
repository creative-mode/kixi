package ao.creativemode.kixi.gamification.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("badges")
public class Badge {
    @Id
    private Long id;
    private String code;
    private String name;
    private String description;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getCode() { return code; }
    public void setCode(String value) { code = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
}

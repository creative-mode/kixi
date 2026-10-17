package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.ProfileSetting;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ProfileSettingRepository extends ReactiveCrudRepository<ProfileSetting, Long> {

    @Query("SELECT visibility FROM user_profile_settings WHERE user_id = :userId")
    Mono<String> findVisibility(Long userId);

    @Modifying
    @Query("""
        INSERT INTO user_profile_settings (user_id, visibility, updated_at)
        VALUES (:userId, :visibility, CURRENT_TIMESTAMP)
        ON CONFLICT (user_id) DO UPDATE SET visibility = :visibility, updated_at = CURRENT_TIMESTAMP
        """)
    Mono<Integer> upsertVisibility(Long userId, String visibility);
}

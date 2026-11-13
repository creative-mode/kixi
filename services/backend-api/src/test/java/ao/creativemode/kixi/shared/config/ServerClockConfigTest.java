package ao.creativemode.kixi.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ServerClockConfigTest {
    @Test
    void serverClockAlwaysUsesLuanda() {
        assertThat(new ServerClockConfig().serverClock().getZone()).isEqualTo(ZoneId.of("Africa/Luanda"));
    }
}

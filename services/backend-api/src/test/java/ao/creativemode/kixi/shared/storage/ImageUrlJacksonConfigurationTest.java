package ao.creativemode.kixi.shared.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ImageUrlJacksonConfigurationTest {

    @Test
    void resolvesPersistedReferencesWithoutChangingOtherStrings() throws Exception {
        ImageStorage storage = mock(ImageStorage.class);
        when(storage.resolveUrl("storage://images/avatar/profile.png"))
                .thenReturn("https://images.example.test/signed-url");
        when(storage.resolveUrl("storage://questions/20/exam.png"))
                .thenReturn("https://images.example.test/should-not-be-issued");
        ObjectMapper mapper = new ObjectMapper().registerModule(
                new ImageUrlJacksonConfiguration().imageUrlModule(storage));

        String json = mapper.writeValueAsString(new Payload(
                "storage://images/avatar/profile.png", "student@example.test", "storage://questions/20/exam.png"));

        assertEquals("{\"photo\":\"https://images.example.test/signed-url\",\"email\":\"student@example.test\","
                + "\"other\":\"storage://questions/20/exam.png\"}", json);
    }

    private record Payload(String photo, String email, String other) {}
}

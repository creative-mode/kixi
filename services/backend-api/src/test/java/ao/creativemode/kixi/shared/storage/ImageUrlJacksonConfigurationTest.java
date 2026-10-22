package ao.creativemode.kixi.shared.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
        when(storage.isManagedImageReference("storage://images/avatar/profile.png")).thenReturn(true);
        when(storage.isManagedImageReference("http://minio:9000/kixi-images/images/avatar/legacy.png"))
                .thenReturn(true);
        ObjectMapper mapper = new ObjectMapper().registerModule(
                new ImageUrlJacksonConfiguration().imageUrlModule(storage));

        String json = mapper.writeValueAsString(new Payload(
                "storage://images/avatar/profile.png", "student@example.test", "storage://questions/20/exam.png",
                "http://minio:9000/kixi-images/questions/20/exam.png", "text containing /images/not-an-object",
                "http://minio:9000/kixi-images/images/avatar/legacy.png"));

        assertEquals("{\"photo\":\"https://images.example.test/signed-url\",\"email\":\"student@example.test\","
                + "\"other\":\"storage://questions/20/exam.png\","
                + "\"questionUrl\":\"http://minio:9000/kixi-images/questions/20/exam.png\","
                + "\"text\":\"text containing /images/not-an-object\","
                + "\"legacyPhoto\":\"http://minio:9000/kixi-images/images/avatar/legacy.png\"}", json);

        verify(storage).resolveUrl("storage://images/avatar/profile.png");
        verify(storage, never()).resolveUrl("storage://questions/20/exam.png");
        verify(storage, never()).resolveUrl("http://minio:9000/kixi-images/questions/20/exam.png");
        verify(storage).resolveUrl("http://minio:9000/kixi-images/images/avatar/legacy.png");
    }

    private record Payload(String photo, String email, String other, String questionUrl, String text,
                           String legacyPhoto) {}
}

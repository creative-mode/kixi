package ao.creativemode.kixi.shared.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Resolves stable storage references only at the API boundary, so persisted values never expire. */
@Configuration(proxyBeanMethods = false)
public class ImageUrlJacksonConfiguration {

    @Bean
    SimpleModule imageUrlModule(ImageStorage storage) {
        SimpleModule module = new SimpleModule("image-url-resolution");
        module.addSerializer(String.class, new JsonSerializer<>() {
            @Override
            public void serialize(String value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                // Profile and institution images may appear in many unrelated DTOs. Question images
                // are resolved only by the statement-authorized QuestionImageService, never here.
                if (!storage.isManagedImageReference(value)) {
                    generator.writeString(value);
                    return;
                }
                String resolved = storage.resolveUrl(value);
                generator.writeString(resolved == null ? value : resolved);
            }
        });
        return module;
    }
}

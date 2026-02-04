package ao.creativemode.kixi.service;

import ao.creativemode.kixi.dto.ChatMessageDto;
import ao.creativemode.kixi.dto.ChatRequestDto;
import ao.creativemode.kixi.dto.ChatResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final WebClient groqWebClient;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    public Mono<ChatResponseDto> chat(ChatRequestDto request) {
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of(
                "role", "system",
                "content", "Você é um assistente educacional chamado Kixi. Ajude os usuários com questões sobre provas, exames e conteúdos educacionais."
        ));

        if (request.getHistory() != null) {
            for (ChatMessageDto msg : request.getHistory()) {
                messages.add(Map.of(
                        "role", msg.getRole(),
                        "content", msg.getContent()
                ));
            }
        }

        messages.add(Map.of(
                "role", "user",
                "content", request.getMessage()
        ));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1024);

        return groqWebClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseResponse);
    }

    @SuppressWarnings("unchecked")
    private ChatResponseDto parseResponse(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");

        String content = "";
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            content = (String) message.get("content");
        }

        Integer totalTokens = usage != null ? (Integer) usage.get("total_tokens") : null;

        return ChatResponseDto.builder()
                .message(content)
                .model((String) response.get("model"))
                .tokensUsed(totalTokens)
                .build();
    }
}

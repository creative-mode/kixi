package ao.creativemode.kixi.controller;

import ao.creativemode.kixi.dto.ChatRequestDto;
import ao.creativemode.kixi.dto.ChatResponseDto;
import ao.creativemode.kixi.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public Mono<ChatResponseDto> chat(@RequestBody ChatRequestDto request) {
        return chatService.chat(request);
    }
}

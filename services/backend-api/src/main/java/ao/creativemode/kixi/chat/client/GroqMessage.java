package ao.creativemode.kixi.chat.client;

/**
 * One chat message in the OpenAI/Groq wire format: {@code role} is
 * {@code system}, {@code user} or {@code assistant}.
 */
public record GroqMessage(String role, String content) {}

package com.virality.engine.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request body for POST /api/posts/{postId}/comments
 *
 * authorId  – UUID of the commenter (User or Bot)
 * isBot     – true if the comment originates from a bot
 * botId     – required when isBot=true (used for cooldown key + virality)
 * humanId   – required when isBot=true (the human whose post is being replied to)
 * depthLevel – 0-based nesting depth; rejected above 20 (HTTP 422)
 */
@Data
public class CreateCommentRequest {

    @NotNull(message = "authorId is required")
    private UUID authorId;

    @NotBlank(message = "content must not be blank")
    private String content;

    @Min(value = 0, message = "depthLevel must be >= 0")
    @Max(value = 20, message = "depthLevel must be <= 20")
    private int depthLevel;

    /** Set true when the commenter is a bot. */
    private boolean bot;

    /**
     * Bot UUID — required when bot=true.
     * Used to build the cooldown Redis key: cooldown:bot_{botId}:human_{humanId}
     */
    private UUID botId;

    /**
     * Human (post-owner) UUID — required when bot=true.
     * Used to build the cooldown Redis key and to trigger notifications.
     */
    private UUID humanId;
}

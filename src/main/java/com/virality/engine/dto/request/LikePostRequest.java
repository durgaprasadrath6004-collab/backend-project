package com.virality.engine.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request body for POST /api/posts/{postId}/like
 *
 * Only human users can like a post (bots cannot like).
 */
@Data
public class LikePostRequest {

    @NotNull(message = "userId is required")
    private UUID userId;
}

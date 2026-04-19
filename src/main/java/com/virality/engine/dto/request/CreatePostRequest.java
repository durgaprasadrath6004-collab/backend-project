package com.virality.engine.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request body for POST /api/posts
 */
@Data
public class CreatePostRequest {

    @NotNull(message = "authorId is required")
    private UUID authorId;

    @NotBlank(message = "content must not be blank")
    private String content;
}

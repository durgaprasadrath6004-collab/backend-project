package com.virality.engine.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CommentResponse {
    private UUID id;
    private UUID postId;
    private UUID authorId;
    private String content;
    private int depthLevel;
    private boolean botComment;
    private Instant createdAt;
    private Long postViralityScore;
}

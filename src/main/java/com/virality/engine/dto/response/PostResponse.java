package com.virality.engine.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class PostResponse {
    private UUID id;
    private UUID authorId;
    private String authorUsername;
    private String content;
    private Instant createdAt;
    private Long viralityScore;
}

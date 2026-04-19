package com.virality.engine.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class LikeResponse {
    private UUID postId;
    private UUID userId;
    private Long viralityScore;
    private String message;
}

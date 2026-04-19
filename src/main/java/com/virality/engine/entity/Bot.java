package com.virality.engine.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * Represents an AI bot that can interact with posts.
 */
@Entity
@Table(name = "bots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bot {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "persona_description", nullable = false, columnDefinition = "TEXT")
    private String personaDescription;
}

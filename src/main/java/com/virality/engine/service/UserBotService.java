package com.virality.engine.service;

import com.virality.engine.entity.Bot;
import com.virality.engine.entity.User;
import com.virality.engine.exception.ResourceNotFoundException;
import com.virality.engine.repository.BotRepository;
import com.virality.engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserBotService {

    private final UserRepository userRepository;
    private final BotRepository  botRepository;

    // ── Users ─────────────────────────────────────────────────────────────

    @Transactional
    public User createUser(String username, boolean isPremium) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        User user = User.builder().username(username).premium(isPremium).build();
        return userRepository.save(user);
    }

    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    // ── Bots ──────────────────────────────────────────────────────────────

    @Transactional
    public Bot createBot(String name, String personaDescription) {
        Bot bot = Bot.builder().name(name).personaDescription(personaDescription).build();
        return botRepository.save(bot);
    }

    public Bot getBot(UUID botId) {
        return botRepository.findById(botId)
                .orElseThrow(() -> new ResourceNotFoundException("Bot not found: " + botId));
    }

    public List<Bot> getAllBots() {
        return botRepository.findAll();
    }
}

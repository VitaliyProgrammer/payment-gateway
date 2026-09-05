package com.payflow.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    @Column(name = "webhook_url")
    private String webhookUrl;

    /**
     * На відміну від api_key_hash (нам досить звірити хеш), цей секрет
     * застосунок мусить уміти прочитати - саме ним ми самі підписуємо кожне
     * вихідне повідомлення вебхука. Тому явний текст, а не хеш.
     */
    @Column(name = "webhook_secret", nullable = false)
    private String webhookSecret;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // JPA
    }

    /**
     * Наразі викликається лише з тестів: демо-мерчант із міграції отримує URL
     * вебхука вже в тестовому середовищі, бо він залежить від порту тестового
     * HTTP-сервера, який заздалегідь у міграції не пропишеш. Повноцінний API
     * для налаштувань мерчанта (заміна URL з дашборду) - за межами цього
     * проєкту.
     */
    public void updateWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

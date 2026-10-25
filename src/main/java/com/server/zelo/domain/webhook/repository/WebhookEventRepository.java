package com.server.zelo.domain.webhook.repository;

import com.server.zelo.domain.webhook.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByWebhookId(String webhookId);
}
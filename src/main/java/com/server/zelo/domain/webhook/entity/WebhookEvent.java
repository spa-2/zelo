package com.server.zelo.domain.webhook.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "webhook_events",
        indexes = @Index(name = "idx_webhook_events_portone_id", columnList = "portone_id")
)
public class WebhookEvent extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 알림을 두 번 처리하지 않도록 포트원 웹훅 ID를 유니크로 둔다
    @Column(name = "webhook_id", nullable = false, unique = true, length = 100)
    private String webhookId;

    @Column(name = "portone_id", nullable = false, length = 50)
    private String portoneId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookStatus status;

    // 처리 로직이 바뀌어도 그때 받은 알림을 다시 확인할 수 있도록 원문을 남긴다
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    private WebhookEvent(String webhookId, String portoneId, String eventType, String payload) {
        this.webhookId = webhookId;
        this.portoneId = portoneId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = WebhookStatus.RECEIVED;
    }

    public static WebhookEvent of(String webhookId, String portoneId, String eventType, String payload) {
        return new WebhookEvent(webhookId, portoneId, eventType, payload);
    }

    public void process(LocalDateTime processedAt) {
        if (isProcessed()) {
            return;
        }
        changeStatus(WebhookStatus.PROCESSED);
        this.processedAt = processedAt;
        this.failureReason = null;
    }

    public void fail(String failureReason) {
        if (isProcessed()) {
            return;
        }
        changeStatus(WebhookStatus.FAILED);
        this.failureReason = failureReason;
    }

    public boolean isProcessed() {
        return status == WebhookStatus.PROCESSED;
    }

    private void changeStatus(WebhookStatus target) {
        if (!status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 웹훅 상태 변경입니다.");
        }
        this.status = target;
    }
}
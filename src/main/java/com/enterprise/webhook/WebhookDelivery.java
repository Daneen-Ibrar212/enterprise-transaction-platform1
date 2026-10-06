package com.enterprise.webhook;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "webhook_config_id", nullable = false)
    private Long webhookConfigId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber = 1;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ===== REPLAY FIELDS =====
    @Column(name = "is_replay", nullable = false)
    private boolean isReplay = false;

    @Column(name = "replay_of_id")
    private Long replayOfId;

    @Column(name = "replay_reason", columnDefinition = "TEXT")
    private String replayReason;

    // ===== RESPONSE TIME (NEW) =====
    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    // ===== Getters and Setters =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getWebhookConfigId() { return webhookConfigId; }
    public void setWebhookConfigId(Long webhookConfigId) { this.webhookConfigId = webhookConfigId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isReplay() { return isReplay; }
    public void setReplay(boolean replay) { isReplay = replay; }

    public Long getReplayOfId() { return replayOfId; }
    public void setReplayOfId(Long replayOfId) { this.replayOfId = replayOfId; }

    public String getReplayReason() { return replayReason; }
    public void setReplayReason(String replayReason) { this.replayReason = replayReason; }

    public Long getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(Long responseTimeMs) { this.responseTimeMs = responseTimeMs; }
}
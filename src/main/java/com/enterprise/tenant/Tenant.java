package com.enterprise.tenant;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    private boolean active = true;

    @Column(name = "base_currency", nullable = false)
    private String baseCurrency = "GBP";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

       @Column(name = "reminder_first_days", nullable = false)
    private int reminderFirstDays = 7;

    @Column(name = "reminder_second_days", nullable = false)
    private int reminderSecondDays = 14;

    @Column(name = "reminder_final_days", nullable = false)
    private int reminderFinalDays = 30;

    @Column(name = "reminder_email_from")
    private String reminderEmailFrom;

    public int getReminderFirstDays() { return reminderFirstDays; }
    public void setReminderFirstDays(int reminderFirstDays) { this.reminderFirstDays = reminderFirstDays; }
    public int getReminderSecondDays() { return reminderSecondDays; }
    public void setReminderSecondDays(int reminderSecondDays) { this.reminderSecondDays = reminderSecondDays; }
    public int getReminderFinalDays() { return reminderFinalDays; }
    public void setReminderFinalDays(int reminderFinalDays) { this.reminderFinalDays = reminderFinalDays; }
    public String getReminderEmailFrom() { return reminderEmailFrom; }
    public void setReminderEmailFrom(String reminderEmailFrom) { this.reminderEmailFrom = reminderEmailFrom; }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getBaseCurrency() { return baseCurrency; }
    public void setBaseCurrency(String baseCurrency) { this.baseCurrency = baseCurrency; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
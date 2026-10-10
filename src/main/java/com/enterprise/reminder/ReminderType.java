package com.enterprise.reminder;

public enum ReminderType {
    FIRST("First Reminder", "invoice-reminder-first"),
    SECOND("Second Reminder", "invoice-reminder-second"),
    FINAL("Final Reminder", "invoice-reminder-final");

    private final String displayName;
    private final String templateKey;

    ReminderType(String displayName, String templateKey) {
        this.displayName = displayName;
        this.templateKey = templateKey;
    }

    public String getDisplayName() { return displayName; }
    public String getTemplateKey() { return templateKey; }
}
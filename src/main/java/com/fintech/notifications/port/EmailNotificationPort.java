package com.fintech.notifications.port;

public interface EmailNotificationPort {

    void sendHtml(
            String recipient,
            String subject,
            String htmlBody
    );
}
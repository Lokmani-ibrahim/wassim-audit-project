package com.fintech.notifications;

public enum NotificationOutboxStatus {

    PENDING,

    PROCESSING,

    PUBLISHED,

    RETRY,

    /*
     * Microsoft Graph accepted the message for processing.
     *
     * This does NOT prove that the destination mailbox
     * ultimately received the email.
     */
    PROVIDER_ACCEPTED,

    DEAD_LETTER
}
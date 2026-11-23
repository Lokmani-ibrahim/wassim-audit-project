package com.fintech.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Invalid pageSize or cursor on GET /api/notifications.
 *
 * Mapped to 400, unlike GET /api/transactions where an
 * out-of-range pageSize currently surfaces as 500.
 */
@ResponseStatus(
        HttpStatus.BAD_REQUEST
)
public class InvalidNotificationFeedRequestException
        extends RuntimeException {

    public InvalidNotificationFeedRequestException(
            String message
    ) {
        super(message);
    }
}

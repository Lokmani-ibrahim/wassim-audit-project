package com.fintech.notifications.api;

import com.fintech.notifications.NotificationFeedPage;
import com.fintech.notifications.NotificationFeedService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Customer in-app notification feed, newest first.
 *
 * Read-only: backed by the existing email outbox
 * (Option A). Read/unread state is kept on the device.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationFeedController {

    private final NotificationFeedService notificationFeedService;

    public NotificationFeedController(
            NotificationFeedService notificationFeedService
    ) {
        this.notificationFeedService =
                Objects.requireNonNull(
                        notificationFeedService,
                        "notificationFeedService must not be null"
                );
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public NotificationFeedPage feed(
            @AuthenticationPrincipal
            Jwt jwt,

            @RequestParam(defaultValue = "20")
            int pageSize,

            @RequestParam(required = false)
            String cursor
    ) {

        return notificationFeedService.getFeed(
                jwt.getSubject(),
                pageSize,
                cursor
        );
    }
}

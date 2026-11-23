package com.fintech.notifications;

import java.util.List;

public record NotificationFeedPage(

        List<NotificationFeedItem> items,
        boolean hasMore,
        String nextCursor

) {
}

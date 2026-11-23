package com.fintech.ledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-caller fixed-window limit on recipient lookups
 * (anti-enumeration).
 *
 * LIMITATION: the counters live in this JVM only.
 * With several backend instances each instance applies
 * its own limit, and counters reset on restart. A shared
 * store (database or cache) is needed before running
 * more than one instance.
 */
@Component
public class RecipientLookupRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    // Above this many tracked callers, expired
    // windows are dropped to bound memory.
    private static final int EVICTION_THRESHOLD = 10_000;

    private final int maxRequestsPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows =
            new ConcurrentHashMap<>();

    @Autowired
    public RecipientLookupRateLimiter(
            @Value("${neos.recipients.lookup.max-requests-per-minute:10}")
            int maxRequestsPerMinute
    ) {
        this(
                maxRequestsPerMinute,
                Clock.systemUTC()
        );
    }

    RecipientLookupRateLimiter(
            int maxRequestsPerMinute,
            Clock clock
    ) {

        if (maxRequestsPerMinute < 1) {
            throw new IllegalArgumentException(
                    "maxRequestsPerMinute must be positive"
            );
        }

        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records one attempt and returns false when the caller
     * has exceeded the limit for the current minute.
     */
    public boolean tryAcquire(String callerKey) {

        Objects.requireNonNull(callerKey, "callerKey");

        long window =
                clock.millis() / WINDOW_MILLIS;

        if (windows.size() > EVICTION_THRESHOLD) {
            windows.values().removeIf(
                    existing -> existing.window() < window
            );
        }

        Window current =
                windows.compute(
                        callerKey,
                        (key, existing) ->
                                existing == null
                                        || existing.window() != window
                                        ? new Window(window, 1)
                                        : new Window(
                                                window,
                                                existing.count() + 1
                                        )
                );

        return current.count() <= maxRequestsPerMinute;
    }

    private record Window(
            long window,
            int count
    ) {
    }
}

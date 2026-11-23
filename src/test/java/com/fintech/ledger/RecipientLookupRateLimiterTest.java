package com.fintech.ledger;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class RecipientLookupRateLimiterTest {

    private static final Instant START =
            Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void allowsUpToTheLimitWithinOneMinute() {

        MutableClock clock = new MutableClock(START);
        RecipientLookupRateLimiter limiter =
                new RecipientLookupRateLimiter(3, clock);

        assertTrue(limiter.tryAcquire("caller"));
        assertTrue(limiter.tryAcquire("caller"));
        assertTrue(limiter.tryAcquire("caller"));
        assertFalse(limiter.tryAcquire("caller"));

        clock.advance(Duration.ofSeconds(59));
        assertFalse(limiter.tryAcquire("caller"));
    }

    @Test
    void resetsInTheNextWindow() {

        MutableClock clock = new MutableClock(START);
        RecipientLookupRateLimiter limiter =
                new RecipientLookupRateLimiter(1, clock);

        assertTrue(limiter.tryAcquire("caller"));
        assertFalse(limiter.tryAcquire("caller"));

        clock.advance(Duration.ofMinutes(1));
        assertTrue(limiter.tryAcquire("caller"));
    }

    @Test
    void limitsEachCallerIndependently() {

        RecipientLookupRateLimiter limiter =
                new RecipientLookupRateLimiter(
                        1,
                        new MutableClock(START)
                );

        assertTrue(limiter.tryAcquire("first"));
        assertFalse(limiter.tryAcquire("first"));
        assertTrue(limiter.tryAcquire("second"));
    }

    @Test
    void rejectsNonPositiveLimit() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new RecipientLookupRateLimiter(0)
        );
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

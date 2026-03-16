package com.velinx.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenMinuteSlotDiscriminatorTest {

    @Test
    void shouldRoundTimestampDownToNearestTenMinuteSlot() {
        long timestamp = Instant.parse("2026-03-15T07:29:59Z").toEpochMilli();

        assertEquals("202603150720", TenMinuteSlotDiscriminator.slotKeyFor(timestamp, ZoneId.of("UTC")));
    }

    @Test
    void shouldKeepExactTenMinuteBoundary() {
        long timestamp = Instant.parse("2026-03-15T07:30:00Z").toEpochMilli();

        assertEquals("202603150730", TenMinuteSlotDiscriminator.slotKeyFor(timestamp, ZoneId.of("UTC")));
    }
}

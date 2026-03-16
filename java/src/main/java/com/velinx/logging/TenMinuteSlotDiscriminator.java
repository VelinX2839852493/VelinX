package com.velinx.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.sift.AbstractDiscriminator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TenMinuteSlotDiscriminator extends AbstractDiscriminator<ILoggingEvent> {

    private static final String KEY = "bucket";
    private static final DateTimeFormatter SLOT_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private ZoneId zoneId = ZoneId.systemDefault();

    @Override
    public String getDiscriminatingValue(ILoggingEvent event) {
        return slotKeyFor(event.getTimeStamp(), zoneId);
    }

    @Override
    public String getKey() {
        return KEY;
    }

    public void setZoneId(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return;
        }
        this.zoneId = ZoneId.of(zoneId);
    }

    static String slotKeyFor(long timestamp, ZoneId zoneId) {
        LocalDateTime localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zoneId);
        int roundedMinute = (localDateTime.getMinute() / 10) * 10;

        return localDateTime
                .withMinute(roundedMinute)
                .withSecond(0)
                .withNano(0)
                .format(SLOT_FORMATTER);
    }
}

package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

public final class AdhanPlanTest {
    private static Calendar at(int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
        c.set(2026, Calendar.OCTOBER, 31, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    @Test public void selectsOnlyEnabledPrayer() {
        Calendar now = at(0, 0);
        AdhanPlan.Event event = AdhanPlan.next(now, PrayerTimes.CITIES[0], 0, 1 << 4);
        assertNotNull(event);
        assertEquals(4, event.prayerIndex);
    }

    @Test public void wrapsToTomorrowAfterLastEnabledPrayer() {
        Calendar now = at(23, 59);
        AdhanPlan.Event event = AdhanPlan.next(now, PrayerTimes.CITIES[0], 0, 1 << 0);
        assertNotNull(event);
        assertEquals(0, event.prayerIndex);
        Calendar when = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
        when.setTimeInMillis(event.whenMillis);
        assertEquals(Calendar.NOVEMBER, when.get(Calendar.MONTH));
        assertEquals(1, when.get(Calendar.DAY_OF_MONTH));
    }
}

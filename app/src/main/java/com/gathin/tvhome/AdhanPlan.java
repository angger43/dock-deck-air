package com.gathin.tvhome;

import java.util.Calendar;

/** Pure scheduling logic shared by the Android alarm layer and JVM tests. */
final class AdhanPlan {
    static final int DEFAULT_MASK = (1 << 0) | (1 << 2) | (1 << 3) | (1 << 4) | (1 << 5);

    static final class Event {
        final long whenMillis;
        final int prayerIndex;

        Event(long whenMillis, int prayerIndex) {
            this.whenMillis = whenMillis;
            this.prayerIndex = prayerIndex;
        }
    }

    private AdhanPlan() {}

    static Event next(Calendar now, PrayerTimes.City city, int method, int mask) {
        Calendar day = (Calendar) now.clone();
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset < 8; offset++) {
            if (offset > 0) day.add(Calendar.DAY_OF_MONTH, 1);
            PrayerTimes times = PrayerTimes.calculate(day, city, method);
            int currentMinute = offset == 0
                    ? now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    : -1;
            for (int i = 0; i < times.minutes.length; i++) {
                if ((mask & (1 << i)) == 0 || times.minutes[i] <= currentMinute) continue;
                Calendar event = (Calendar) day.clone();
                event.set(Calendar.HOUR_OF_DAY, times.minutes[i] / 60);
                event.set(Calendar.MINUTE, times.minutes[i] % 60);
                return new Event(event.getTimeInMillis(), i);
            }
        }
        return null;
    }
}

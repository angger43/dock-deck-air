package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.Calendar;

/** Schedules one exact wakeup alarm at a time and rebuilds it after every relevant state change. */
final class AdhanScheduler {
    static final String PREFS = "nothing_tv";
    static final String KEY_ENABLED = "adhan_enabled";
    static final String KEY_VOLUME = "adhan_volume";
    static final String KEY_AUDIO = "adhan_audio";
    static final String KEY_MASK = "adhan_prayers";
    static final String ACTION_ALARM = "com.gathin.tvhome.ADHAN_ALARM";
    static final String EXTRA_WHEN = "when";
    static final String EXTRA_PRAYER = "prayer";
    private static final int REQUEST = 4701;

    private AdhanScheduler() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void ensureDefaults(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean("adhan_initialized", false)) return;
        p.edit().putBoolean("adhan_initialized", true)
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_VOLUME, 65)
                .putInt(KEY_AUDIO, 0)
                .putInt(KEY_MASK, AdhanPlan.DEFAULT_MASK)
                .apply();
    }

    static void schedule(Context context) {
        ensureDefaults(context);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent pending = pending(context, PendingIntent.FLAG_UPDATE_CURRENT);
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_ENABLED, true)) {
            alarm.cancel(pending);
            return;
        }
        int cityIndex = bounded(p.getInt("prayer_city", 0), PrayerTimes.CITIES.length);
        int method = bounded(p.getInt("prayer_method", 0), PrayerTimes.METHODS.length);
        AdhanPlan.Event event = AdhanPlan.next(Calendar.getInstance(), PrayerTimes.CITIES[cityIndex], method,
                p.getInt(KEY_MASK, AdhanPlan.DEFAULT_MASK));
        if (event == null) return;
        Intent intent = new Intent(context, AdhanReceiver.class).setAction(ACTION_ALARM)
                .putExtra(EXTRA_WHEN, event.whenMillis).putExtra(EXTRA_PRAYER, event.prayerIndex);
        pending = PendingIntent.getBroadcast(context, REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 31 && alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.whenMillis, pending);
        } else if (Build.VERSION.SDK_INT >= 23) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.whenMillis, pending);
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, event.whenMillis, pending);
        }
        p.edit().putLong("adhan_next_when", event.whenMillis)
                .putInt("adhan_next_prayer", event.prayerIndex).apply();
    }

    static void receive(Context context, Intent intent) {
        SharedPreferences p = prefs(context);
        int prayer = intent.getIntExtra(EXTRA_PRAYER, -1);
        long expected = intent.getLongExtra(EXTRA_WHEN, 0L);
        int mask = p.getInt(KEY_MASK, AdhanPlan.DEFAULT_MASK);
        long drift = Math.abs(System.currentTimeMillis() - expected);
        if (!p.getBoolean(KEY_ENABLED, true) || prayer < 0 || prayer >= PrayerTimes.NAMES.length
                || (mask & (1 << prayer)) == 0 || drift > 4 * 60_000L) {
            schedule(context);
            return;
        }
        try { startPlayback(context, prayer, expected, false); }
        finally { schedule(context); }
    }

    static void toggleTest(Context context) {
        if (AdhanPlaybackService.isPlaying()) stop(context);
        else startPlayback(context, -1, System.currentTimeMillis(), true);
    }

    static void stop(Context context) {
        context.startService(new Intent(context, AdhanPlaybackService.class)
                .setAction(AdhanPlaybackService.ACTION_STOP));
    }

    static boolean exactAccess(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarm != null && alarm.canScheduleExactAlarms();
    }

    static Intent exactAccessIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + context.getPackageName()));
    }

    private static void startPlayback(Context context, int prayer, long expected, boolean test) {
        Intent service = new Intent(context, AdhanPlaybackService.class)
                .putExtra(EXTRA_PRAYER, prayer).putExtra(EXTRA_WHEN, expected)
                .putExtra("test", test);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }

    private static PendingIntent pending(Context context, int flags) {
        Intent intent = new Intent(context, AdhanReceiver.class).setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(context, REQUEST, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int bounded(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}

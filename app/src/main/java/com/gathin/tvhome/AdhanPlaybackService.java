package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Single foreground owner for Adhan audio, popup, notification and immediate Stop. */
public final class AdhanPlaybackService extends Service {
    static final String ACTION_STOP = "com.gathin.tvhome.STOP_ADHAN";
    static final String ACTION_STATE = "com.gathin.tvhome.ADHAN_PLAYBACK_STATE";
    private static final String CHANNEL = "adhan_alerts_v2";
    private static final int NOTIFICATION = 4702;
    private static volatile boolean active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;

    static boolean isPlaying() { return active; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int prayer = intent == null ? -1 : intent.getIntExtra(AdhanScheduler.EXTRA_PRAYER, -1);
        long expected = intent == null ? 0 : intent.getLongExtra(AdhanScheduler.EXTRA_WHEN, 0L);
        boolean test = intent != null && intent.getBooleanExtra("test", false);
        String prayerName = test ? "Test Adhan" : prayer >= 0 && prayer < PrayerTimes.NAMES.length
                ? PrayerTimes.NAMES[prayer] : "Adhan";
        long displayAt = expected > 0 ? expected : System.currentTimeMillis();
        String prayerTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(displayAt));
        String title = test ? prayerName : prayerName + " Adhan";

        SharedPreferences prefs = AdhanScheduler.prefs(this);
        String key = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(displayAt)) + ":" + prayer;
        synchronized (AdhanPlaybackService.class) {
            String last = prefs.getString("adhan_last_event", "");
            long lastAt = prefs.getLong("adhan_last_at", 0L);
            if (active) return START_NOT_STICKY;
            if (!test && key.equals(last) && System.currentTimeMillis() - lastAt < 20 * 60_000L) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            active = true;
        }

        startForeground(NOTIFICATION, notification(title, prayerName, prayerTime));
        play(prefs, prayerName, prayerTime, test, key);
        return START_NOT_STICKY;
    }

    private void play(SharedPreferences prefs, String prayerName, String prayerTime,
            boolean test, String eventKey) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && audio != null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).build();
            audio.requestAudioFocus(focusRequest);
        }

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":adhan");
            wakeLock.acquire(10 * 60_000L);
        }

        player = MediaPlayer.create(this, R.raw.adhan_full, attributes, 0);
        if (player == null) { stopSelf(); return; }
        float volume = Math.max(0.05f,
                Math.min(1f, prefs.getInt(AdhanScheduler.KEY_VOLUME, 65) / 100f));
        player.setVolume(volume, volume);
        player.setOnCompletionListener(mp -> stopSelf());
        player.setOnErrorListener((mp, what, extra) -> { stopSelf(); return true; });
        try {
            player.start();
        } catch (RuntimeException failed) {
            stopSelf();
            return;
        }

        if (!test) prefs.edit().putString("adhan_last_event", eventKey)
                .putLong("adhan_last_at", System.currentTimeMillis()).apply();
        handler.postDelayed(() -> rememberRoute(prefs), 500L);
        sendState(true);
        showPopup(prayerName, prayerTime);
        if (prefs.getInt(AdhanScheduler.KEY_AUDIO, 0) == 1)
            handler.postDelayed(this::stopSelf, 20_000L);
    }

    private void rememberRoute(SharedPreferences prefs) {
        if (player == null || Build.VERSION.SDK_INT < 28) return;
        AudioDeviceInfo route = player.getRoutedDevice();
        String value = route == null ? "System default"
                : String.valueOf(route.getProductName()) + " · type=" + route.getType();
        prefs.edit().putString("adhan_last_route", value).apply();
    }

    private void showPopup(String prayerName, String prayerTime) {
        Intent popup = popupIntent(prayerName, prayerTime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(popup); }
        catch (RuntimeException ignored) { /* The full-screen alarm notification remains the fallback. */ }
    }

    private Intent popupIntent(String prayerName, String prayerTime) {
        return new Intent(this, AdhanPopupActivity.class)
                .putExtra(AdhanPopupActivity.EXTRA_NAME, prayerName)
                .putExtra(AdhanPopupActivity.EXTRA_TIME, prayerTime);
    }

    private Notification notification(String title, String prayerName, String prayerTime) {
        PendingIntent popup = PendingIntent.getActivity(this, 4703,
                popupIntent(prayerName, prayerTime),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 4704,
                new Intent(this, AdhanPlaybackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Stop Adhan", stop).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(prayerTime + " · Playing on the TV audio output")
                .setContentIntent(popup)
                .setFullScreenIntent(popup, true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Adhan alerts", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Shows the prayer name and a remote-accessible Stop button during Adhan");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private void sendState(boolean playing) {
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("playing", playing));
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) {}
            player.release();
            player = null;
        }
        if (Build.VERSION.SDK_INT >= 26 && audio != null && focusRequest != null)
            audio.abandonAudioFocusRequest(focusRequest);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        synchronized (AdhanPlaybackService.class) { active = false; }
        sendState(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

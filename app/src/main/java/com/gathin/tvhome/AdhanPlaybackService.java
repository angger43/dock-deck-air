package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Foreground media playback with duplicate, standby and lifecycle guards. */
public final class AdhanPlaybackService extends Service {
    private static final String CHANNEL = "adhan";
    private static final int NOTIFICATION = 4702;
    private static boolean active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean screenReceiverRegistered;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopSelf(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        screenReceiverRegistered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int prayer = intent == null ? -1 : intent.getIntExtra(AdhanScheduler.EXTRA_PRAYER, -1);
        long expected = intent == null ? 0 : intent.getLongExtra(AdhanScheduler.EXTRA_WHEN, 0L);
        boolean test = intent != null && intent.getBooleanExtra("test", false);
        String title = test ? "Test Adhan" : prayer >= 0 && prayer < PrayerTimes.NAMES.length
                ? PrayerTimes.NAMES[prayer] + " Adhan" : "Adhan";
        startForeground(NOTIFICATION, notification(title));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (!test && (power == null || !power.isInteractive())) { stopSelf(); return START_NOT_STICKY; }
        SharedPreferences prefs = AdhanScheduler.prefs(this);
        String key = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(expected)) + ":" + prayer;
        synchronized (AdhanPlaybackService.class) {
            String last = prefs.getString("adhan_last_event", "");
            long lastAt = prefs.getLong("adhan_last_at", 0L);
            if (active) return START_NOT_STICKY;
            if (!test && key.equals(last) && System.currentTimeMillis() - lastAt < 20 * 60_000L) {
                stopSelf(startId); return START_NOT_STICKY;
            }
            active = true;
            if (!test) prefs.edit().putString("adhan_last_event", key)
                    .putLong("adhan_last_at", System.currentTimeMillis()).apply();
        }
        play(prefs, title);
        return START_NOT_STICKY;
    }

    private void play(SharedPreferences prefs, String title) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && audio != null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes).build();
            audio.requestAudioFocus(focusRequest);
        }
        player = MediaPlayer.create(this, R.raw.adhan_full, attributes, 0);
        if (player == null) { stopSelf(); return; }
        boolean speakerRequested = false;
        if (audio != null && Build.VERSION.SDK_INT >= 28) {
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    speakerRequested = player.setPreferredDevice(device);
                    prefs.edit().putString("adhan_last_route",
                            String.valueOf(device.getProductName()) + " · requested=" + speakerRequested).apply();
                    break;
                }
            }
        }
        if (!speakerRequested && !prefs.contains("adhan_last_route"))
            prefs.edit().putString("adhan_last_route", "System default").apply();
        float volume = Math.max(0.05f, Math.min(1f, prefs.getInt(AdhanScheduler.KEY_VOLUME, 65) / 100f));
        player.setVolume(volume, volume);
        player.setOnCompletionListener(mp -> stopSelf());
        player.setOnErrorListener((mp, what, extra) -> { stopSelf(); return true; });
        player.start();
        if (prefs.getInt(AdhanScheduler.KEY_AUDIO, 0) == 1) handler.postDelayed(this::stopSelf, 20_000L);
    }

    private Notification notification(String title) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText("Playing through the TV audio output")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Adhan playback", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) { try { player.stop(); } catch (RuntimeException ignored) {} player.release(); player = null; }
        if (Build.VERSION.SDK_INT >= 26 && audio != null && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        if (screenReceiverRegistered) { unregisterReceiver(screenOff); screenReceiverRegistered = false; }
        synchronized (AdhanPlaybackService.class) { active = false; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

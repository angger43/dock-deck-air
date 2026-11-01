package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;

/** Applies Android Private DNS without a VPN, polling service, or network permission. */
final class AdBlockingManager {
    static final String HOST = "dns.adguard-dns.com";
    static final String PRIVATE_DNS_ACTION = "android.settings.PRIVATE_DNS_SETTINGS";
    private static final String MODE_KEY = "private_dns_mode";
    private static final String SPECIFIER_KEY = "private_dns_specifier";
    private static final String BACKUP_READY = "adblock_dns_backup_ready";
    private static final String BACKUP_MODE_PRESENT = "adblock_dns_mode_present";
    private static final String BACKUP_MODE = "adblock_dns_mode";
    private static final String BACKUP_SPECIFIER_PRESENT = "adblock_dns_specifier_present";
    private static final String BACKUP_SPECIFIER = "adblock_dns_specifier";
    private static final String LAST_ERROR = "adblock_dns_last_error";

    static final class Status {
        final DnsProtectionState.Result state;
        final boolean canWrite;
        final String error;

        Status(DnsProtectionState.Result state, boolean canWrite, String error) {
            this.state = state;
            this.canWrite = canWrite;
            this.error = error;
        }
    }

    private final Activity activity;
    private final SharedPreferences prefs;

    AdBlockingManager(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
    }

    Status status() {
        ContentResolver resolver = activity.getContentResolver();
        String mode = Settings.Global.getString(resolver, MODE_KEY);
        String specifier = Settings.Global.getString(resolver, SPECIFIER_KEY);
        boolean connected = false;
        boolean privateDnsActive = false;
        String activeServer = null;
        try {
            ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager == null || network == null
                    ? null : manager.getNetworkCapabilities(network);
            LinkProperties properties = manager == null || network == null
                    ? null : manager.getLinkProperties(network);
            connected = capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && properties != null) {
                privateDnsActive = properties.isPrivateDnsActive();
                activeServer = properties.getPrivateDnsServerName();
            }
        } catch (RuntimeException ignored) {
            // The global Settings values still provide an honest selected/off state.
        }
        return new Status(DnsProtectionState.evaluate(mode, specifier, connected,
                privateDnsActive, activeServer), canWrite(), prefs.getString(LAST_ERROR, ""));
    }

    boolean enable() {
        if (!canWrite()) return fail("Android permission required; open TV Settings instead.");
        ContentResolver resolver = activity.getContentResolver();
        String originalMode = Settings.Global.getString(resolver, MODE_KEY);
        String originalSpecifier = Settings.Global.getString(resolver, SPECIFIER_KEY);
        if (!prefs.getBoolean(BACKUP_READY, false)) saveOriginal(originalMode, originalSpecifier);
        try {
            if (!writePair(resolver, "hostname", HOST)) {
                writePair(resolver, originalMode, originalSpecifier);
                return fail("The TV rejected the Private DNS change.");
            }
            prefs.edit().remove(LAST_ERROR).apply();
            return true;
        } catch (SecurityException | IllegalArgumentException error) {
            try { writePair(resolver, originalMode, originalSpecifier); }
            catch (RuntimeException ignored) {}
            return fail("The TV blocked the Private DNS change.");
        }
    }

    boolean disable() {
        if (!canWrite()) return fail("Android permission required; open TV Settings instead.");
        if (!prefs.getBoolean(BACKUP_READY, false)) {
            return fail("Original DNS was not recorded; restore it in TV Settings.");
        }
        String mode = prefs.getBoolean(BACKUP_MODE_PRESENT, false)
                ? prefs.getString(BACKUP_MODE, null) : null;
        String specifier = prefs.getBoolean(BACKUP_SPECIFIER_PRESENT, false)
                ? prefs.getString(BACKUP_SPECIFIER, null) : null;
        try {
            if (!writePair(activity.getContentResolver(), mode, specifier)) {
                return fail("The TV rejected the original DNS settings.");
            }
            prefs.edit().remove(BACKUP_READY).remove(BACKUP_MODE_PRESENT).remove(BACKUP_MODE)
                    .remove(BACKUP_SPECIFIER_PRESENT).remove(BACKUP_SPECIFIER)
                    .remove(LAST_ERROR).apply();
            return true;
        } catch (SecurityException | IllegalArgumentException error) {
            return fail("The TV blocked restoring the original DNS settings.");
        }
    }

    void openSupportedSettings() {
        try {
            activity.startActivity(new Intent(PRIVATE_DNS_ACTION));
        } catch (RuntimeException unavailable) {
            try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) {}
        }
    }

    private boolean canWrite() {
        return activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void saveOriginal(String mode, String specifier) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(BACKUP_READY, true)
                .putBoolean(BACKUP_MODE_PRESENT, mode != null)
                .putBoolean(BACKUP_SPECIFIER_PRESENT, specifier != null);
        if (mode != null) editor.putString(BACKUP_MODE, mode); else editor.remove(BACKUP_MODE);
        if (specifier != null) editor.putString(BACKUP_SPECIFIER, specifier);
        else editor.remove(BACKUP_SPECIFIER);
        editor.apply();
    }

    private static boolean writePair(ContentResolver resolver, String mode, String specifier) {
        // Specifier first prevents a transient hostname mode with an empty provider.
        boolean specifierWritten = Settings.Global.putString(resolver, SPECIFIER_KEY, specifier);
        boolean modeWritten = Settings.Global.putString(resolver, MODE_KEY, mode);
        return specifierWritten && modeWritten;
    }

    private boolean fail(String message) {
        prefs.edit().putString(LAST_ERROR, message).apply();
        return false;
    }
}

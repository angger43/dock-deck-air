package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarm delivery endpoint. It never wakes the display and delegates playback to a foreground service. */
public final class AdhanReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && AdhanScheduler.ACTION_ALARM.equals(intent.getAction())) {
            AdhanScheduler.receive(context, intent);
        } else {
            AdhanScheduler.schedule(context);
        }
    }
}

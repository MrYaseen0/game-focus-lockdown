package com.gamefocus.locker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restarts the booster after a reboot / app update when lockdown is still enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (!Prefs.isEnabled(ctx) || Prefs.userStopped(ctx)) return;
        try {
            Intent it = new Intent(ctx, BoosterService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it); else ctx.startService(it);
            BoosterService.applyDnd(ctx);
        } catch (Exception ignore) {}
    }
}
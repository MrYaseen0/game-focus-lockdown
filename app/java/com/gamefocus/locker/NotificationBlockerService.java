package com.gamefocus.locker;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification cleaner (belt & braces on top of total DND). While FOCUS mode is
 * on, ANY notification posted by a non-whitelisted app (WhatsApp, SMS, mail...)
 * is removed the moment it appears, AND the tray is re-swept every 2 seconds so
 * even notifications that were already posted (or sneak past a filter change)
 * never stay visible. Game media audio is untouched — only the tray is cleared.
 */
public class NotificationBlockerService extends NotificationListenerService {

    public static volatile boolean connected = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean sweeping = false;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        startSweep();
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        cancelIfForeign(sbn);
        startSweep();
    }

    private void startSweep() {
        if (sweeping) return;
        sweeping = true;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (Prefs.isEnabled(NotificationBlockerService.this)) {
                        try {
                            StatusBarNotification[] active = getActiveNotifications();
                            if (active != null) for (StatusBarNotification sbn : active) cancelIfForeign(sbn);
                        } catch (Exception ignore) {}
                    }
                } finally {
                    sweeping = false;
                    startSweep();
                }
            }
        }, 2000);
    }

    private void cancelIfForeign(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        if (!Prefs.isEnabled(this)) return;
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) return; // our own "LOCKDOWN ACTIVE" notice stays
        if (Prefs.whitelistedPackages(this).contains(pkg)) return;
        try {
            cancelNotification(sbn.getKey());
        } catch (Exception ignore) {}
    }
}

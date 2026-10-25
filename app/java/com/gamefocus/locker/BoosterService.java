package com.gamefocus.locker;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * THE ENGINE. While FOCUS mode is on this foreground service, every 2 seconds:
 *  1. kills every process that is not a whitelisted game — including visible /
 *     recent apps (aggressive mode) — every 2 seconds;
 *  2. enforces Do-Not-Disturb in PRIORITY mode so no call / SMS / WhatsApp
 *     notification can appear (media audio of the games is NOT touched —
 *     game sound always works);
 *  3. auto-rejects any incoming phone call ( TelecomManager.endCall ).
 * The AccessibilityService (watchdog) handles any app that comes to the foreground.
 */
public class BoosterService extends Service {

    static final String CHANNEL_ID = "focus";
    static final int NOTIF_ID = 1;
    /** apps whose processes are never killed (games list lives in Prefs.whitelistedPackages) */
    private static final Set<String> EXTRA_KEEP = new HashSet<>();
    static {
        // essential system processes a public app cannot kill anyway, but be explicit
        EXTRA_KEEP.add("com.android.systemui");
        EXTRA_KEEP.add("android");
        EXTRA_KEEP.add("com.samsung.android.game.gamehome");
        EXTRA_KEEP.add("com.samsung.android.game.gos");
        EXTRA_KEEP.add("com.samsung.android.game.gametools");
        EXTRA_KEEP.add("com.google.android.webview");
        EXTRA_KEEP.add("com.google.android.gms");
        EXTRA_KEEP.add("com.google.android.gsf");
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityManager am;
    private NotificationManager nm;
    private TelecomManager telecom;
    private TelephonyManager tm;
    private Object telephonyCallback; // TelephonyCallback (API31+) or legacy PhoneStateListener
    private boolean killLoopRunning = false;

    private final Runnable killCycle = new Runnable() {
        @Override public void run() {
            if (Prefs.isEnabled(BoosterService.this)) {
                executor.execute(() -> {
                    // never kill while a protected game is in the foreground:
                    // its background services (plugin, audio, net) must survive
                    boolean gameInFg = false;
                    try {
                        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                            if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                                for (String pkg : p.pkgList) {
                                    if (Prefs.whitelistedPackages(BoosterService.this).contains(pkg)
                                            && !pkg.equals(getPackageName())) { gameInFg = true; break; }
                                }
                            }
                            if (gameInFg) break;
                        }
                    } catch (Exception ignore) {}
                    if (!gameInFg) killBackground(BoosterService.this, true);
                    applyDnd(BoosterService.this);
                });
            }
            handler.postDelayed(this, 10000);
        }
    };

    static void killBackgroundNow(Context ctx) {
        killBackground(ctx, false);
        reEnforceDnd(ctx);
    }

    /** Apply a sound-safe lockdown: PRIORITY DND but with an explicit policy that
     *  KEEPS media (game audio) allowed, while calls / SMS / WhatsApp stay blocked.
     *  Samsung defaults can mute media in priority mode — setting the policy fixes it. */
    static void applyDnd(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.isNotificationPolicyAccessGranted()) return;
            int cats = NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
                    | NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
                    | NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM;
            NotificationManager.Policy policy = new NotificationManager.Policy(
                    cats,
                    NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                    NotificationManager.Policy.PRIORITY_SENDERS_ANY);
            nm.setNotificationPolicy(policy);
            if (nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            }
        } catch (Exception ignore) {}
    }

    /** Re-forces DND in PRIORITY mode (sound-safe). */
    static void reEnforceDnd(Context ctx) { applyDnd(ctx); }

    /**
     * "stop all bg process" engine. Kills background processes of every non-whitelisted app.
     * aggressive=false: the normal loop — visible apps are left alone, only true background dies.
     * aggressive=true : also attacks visible / recent / foreground-service processes
     *                   (Android still refuses to kill the app you are currently touching,
     *                   so the screen you are on is never interrupted). Whitelist always wins.
     * @return number of killBackgroundProcesses calls that went through
     */
    static int killBackground(Context ctx, boolean aggressive) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> keep = Prefs.whitelistedPackages(ctx);
        keep.addAll(EXTRA_KEEP);
        int kills = 0;
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                int threshold = aggressive
                        ? ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        : ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.importance <= threshold) continue; // never touch the app on screen
                    for (String pkg : p.pkgList) {
                        if (keep.contains(pkg)) continue;
                        try { am.killBackgroundProcesses(pkg); kills++; } catch (Exception ignore) {}
                    }
                }
            }
        } catch (Exception ignore) {}
        if (kills > 0) Prefs.addKills(ctx, kills);
        return kills;
    }

    // ---------------- lifecycle ----------------

    @Override public void onCreate() {
        super.onCreate();
        am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        telecom = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        applyDnd(this);
        synchronized (this) {
            if (!killLoopRunning) {
                killLoopRunning = true;
                handler.postDelayed(killCycle, 1000);
            }
            listenCalls(true);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(killCycle);
        listenCalls(false);
        restoreSound();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------------- pieces ----------------

    private void startForegroundCompat() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "FOCUS mode",
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Keeps the booster alive while gaming");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GAME FOCUS: LOCKDOWN ACTIVE")
                .setContentText("Killing background apps - only PUBG / COD run. Sound stays on.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    /** silence everything except media; called on focus OFF too */
    void restoreSound() {
        try {
            if (nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            }
        } catch (Exception ignore) {}
    }

    private void listenCalls(boolean start) {
        if (tm == null) return;
        boolean phonePerm = checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        if (!phonePerm) return;
        try {
            if (start && telephonyCallback == null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    final class Cb extends TelephonyCallback
                            implements TelephonyCallback.CallStateListener {
                        @Override public void onCallStateChanged(int state) {
                            BoosterService.this.onCallStateChanged(state);
                        }
                    }
                    Cb cb = new Cb();
                    tm.registerTelephonyCallback(getMainExecutor(), cb);
                    telephonyCallback = cb;
                } else {
                    PhoneStateListener legacy = new PhoneStateListener() {
                        @Override public void onCallStateChanged(int state, String phoneNumber) {
                            BoosterService.this.onCallStateChanged(state);
                        }
                    };
                    tm.listen(legacy, PhoneStateListener.LISTEN_CALL_STATE);
                    telephonyCallback = legacy;
                }
            } else if (!start) {
                if (telephonyCallback instanceof TelephonyCallback) {
                    tm.unregisterTelephonyCallback((TelephonyCallback) telephonyCallback);
                } else if (telephonyCallback instanceof PhoneStateListener) {
                    tm.listen((PhoneStateListener) telephonyCallback, PhoneStateListener.LISTEN_NONE);
                }
                telephonyCallback = null;
            }
        } catch (Exception ignore) {}
    }

    void onCallStateChanged(int state) {
        if (state != TelephonyManager.CALL_STATE_RINGING) return;
        try {
            boolean canEnd = checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED;
            if (canEnd && telecom != null && telecom.endCall()) {
                Prefs.addCallRejected(this);
            }
        } catch (Exception ignore) {}
    }
}

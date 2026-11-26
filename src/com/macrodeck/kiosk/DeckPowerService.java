package com.macrodeck.kiosk;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lie l'écran de la tablette à l'état du PC.
 *
 * Sonde l'adresse du deck toutes les 4 s, même écran éteint (service au premier plan
 * + verrou CPU partiel) :
 *
 * - le PC répond de nouveau (démarrage, sortie de veille) → réveil de l'écran et
 *   rechargement du deck ;
 * - le PC ne répond plus pendant ~20 s (arrêt, veille, hibernation) → extinction de
 *   l'écran, immédiate si l'app est administrateur de l'appareil, sinon au bout du
 *   délai de veille du système.
 *
 * Un AlarmManager relance le service toutes les 10 minutes si le constructeur l'a
 * arrêté (MIUI/HyperOS tue volontiers les services pour économiser la batterie).
 */
public class DeckPowerService extends Service {

    static final String CHANNEL_ID = "kiosk";
    static final int NOTIFICATION_ID = 42;

    /** Dernier état connu du PC, lisible par l'activité (même processus). */
    static volatile boolean pcOnline = true;
    /** L'écran a été rendu au système parce que le PC est éteint. */
    static volatile boolean sleeping = false;

    private static final String REVIVE_ACTION = "com.macrodeck.kiosk.REVIVE";
    private static final long POLL_MS = 4000;
    private static final long OFF_DELAY_MS = 20000;
    private static final int TIMEOUT_MS = 2500;
    private static final long REVIVE_MS = 10 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock cpuLock;
    private boolean known;
    private boolean online = true;
    private long offlineSince;
    private boolean inFlight;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** Un tour de boucle : sonde le PC si l'option « écran lié au PC » est active. */
    private void step() {
        if (!linked()) {
            // Option désactivée : aucune sonde, aucun verrou CPU (batterie préservée) ;
            // si l'écran avait été rendu au système, on le rend de nouveau à l'app.
            if (sleeping) {
                sleeping = false;
                wakeTablet();
            }
            known = false;
            releaseCpu();
            return;
        }
        holdCpu();
        probe();
    }

    /** Option de l'app : lier l'écran de la tablette à l'état du PC (activée par défaut). */
    private boolean linked() {
        if (!getSharedPreferences("kiosk", MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_POWER_LINK, true)) {
            return false;
        }
        // Sans « Afficher par-dessus les autres applis », Android 10+ interdit de
        // rallumer l'écran depuis l'arrière-plan : mieux vaut ne pas l'éteindre.
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void holdCpu() {
        if (cpuLock != null && !cpuLock.isHeld()) {
            cpuLock.acquire();
        }
    }

    private void releaseCpu() {
        if (cpuLock != null && cpuLock.isHeld()) {
            cpuLock.release();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kiosk:probe");
            cpuLock.setReferenceCounted(false);
        }
        startForeground(NOTIFICATION_ID,
                notification("Surveille le PC : la tablette s'allume et s'éteint avec lui"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(tick);
        handler.post(tick);
        scheduleRevive();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseCpu();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Sonde réseau                                                        */
    /* ------------------------------------------------------------------ */

    private String deckUrl() {
        SharedPreferences prefs = getSharedPreferences("kiosk", MODE_PRIVATE);
        return prefs.getString("url", null);
    }

    private void probe() {
        if (inFlight) {
            return;
        }
        final String url = deckUrl();
        if (url == null) {
            return;
        }
        inFlight = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean up = reachable(url);
                inFlight = false;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        apply(up);
                    }
                });
            }
        }).start();
    }

    private static boolean reachable(String base) {
        HttpURLConnection conn = null;
        try {
            URL target = new URL(base + "/");
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            // 200 = deck servi ; 401 = page de connexion Macro Deck : le PC est vivant.
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void apply(boolean up) {
        if (!known) {
            known = true;
            online = up;
            pcOnline = up;
            if (!up) {
                offlineSince = System.currentTimeMillis();
            }
            return;
        }
        pcOnline = up;
        if (up == online) {
            if (!up) {
                maybeSleep(false);
            }
            return;
        }
        online = up;
        if (up) {
            offlineSince = 0;
            wakeTablet();
        } else {
            offlineSince = System.currentTimeMillis();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Écran                                                               */
    /* ------------------------------------------------------------------ */

    /** Le PC répond de nouveau : réveil de l'écran et relance du deck. */
    private void wakeTablet() {
        sleeping = false;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        try {
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock show = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "kiosk:show");
                show.acquire(5000);
            }
        } catch (Exception ignored) {
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra(MainActivity.EXTRA_WAKE, true);
        try {
            startActivity(open);
        } catch (Exception ignored) {
            // Android 10+ refuse un démarrage d'activité en arrière-plan sans
            // l'autorisation « Afficher par-dessus les autres applis ».
        }
    }

    /** Le PC ne répond plus : extinction de l'écran. */
    private void maybeSleep(boolean force) {
        if (sleeping && !force) {
            return;
        }
        long since = offlineSince == 0 ? System.currentTimeMillis() : offlineSince;
        if (!force && System.currentTimeMillis() - since < OFF_DELAY_MS) {
            return;
        }
        sleeping = true;
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskAdmin.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow();
                return;
            } catch (Exception ignored) {
                // Admin révoqué entre-temps : on retombe sur la veille système.
            }
        }
        Intent sleep = new Intent(this, MainActivity.class);
        sleep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        sleep.putExtra(MainActivity.EXTRA_SLEEP, true);
        try {
            startActivity(sleep);
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* Notification + relance                                              */
    /* ------------------------------------------------------------------ */

    private Notification notification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Écran lié au PC", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent content = PendingIntent.getActivity(this, 1, open, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("Macro Deck")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(content)
                .setPriority(Notification.PRIORITY_MIN);
        return builder.build();
    }

    private void scheduleRevive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent revive = new Intent(this, DeckPowerService.class);
            revive.setAction(REVIVE_ACTION);
            int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getService(
                    this, 7, revive, flags | PendingIntent.FLAG_UPDATE_CURRENT);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + REVIVE_MS, pi);
            }
        } catch (Exception ignored) {
        }
    }
}

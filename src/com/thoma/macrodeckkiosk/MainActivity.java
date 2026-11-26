package com.thoma.macrodeckkiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Kiosque plein écran pour le web client de Macro Deck 3.
 *
 * - affiche le deck sans aucune barre de navigateur ni barre système ;
 * - garde l'écran allumé ;
 * - se reconnecte tout seul au PC (toutes les 5 s) quand celui-ci est éteint ;
 * - suit l'état du PC : l'écran s'allume quand le PC démarre et s'éteint quand il
 *   s'arrête (voir DeckPowerService) ;
 * - mémorise l'adresse du PC (Volume + et Volume − en même temps pour la changer) ;
 * - deux appuis sur Retour pour quitter.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "kiosk";
    private static final String KEY_URL = "url";
    /** Option : lier l'écran de la tablette à l'état du PC (activée par défaut). */
    static final String KEY_POWER_LINK = "power_link";
    // Volontairement vide : l'APK ne contient aucune adresse personnelle.
    // Au premier lancement, l'app demande l'adresse du PC sur la tablette.
    private static final String DEFAULT_URL = "";
    private static final String URL_EXAMPLE = "192.168.1.50:8193";
    private static final int RETRY_MS = 5000;
    private static final int WATCHDOG_MS = 20000;
    private static final int RELOAD_AFTER_MS = 60000;
    private static final int DECK_CHECK_MS = 6000;
    private static final int MAX_EMPTY_RELOADS = 2;

    // Purge le cache applicatif (service worker + Cache Storage) sans toucher au
    // localStorage, qui contient l'identité de l'appareil et le choix du profil :
    // un cache périmé affichait le deck avec des cases grises après une mise à jour.
    private static final String CLEAR_CACHE_JS =
            "(function(){try{if('serviceWorker' in navigator){navigator.serviceWorker.getRegistrations()"
            + ".then(function(rs){rs.forEach(function(r){r.unregister();});});}}catch(e){}"
            + "try{if(window.caches){caches.keys().then(function(ks){ks.forEach(function(k){caches.delete(k);});});}}catch(e){}"
            + "})();";

    // Compte les cases réellement rendues : 0 (ou presque) = deck vide / grille grise.
    private static final String COUNT_WIDGETS_JS =
            "(function(){var t=(document.body&&document.body.innerText)||'';"
            + "if(t.indexOf('Sign in')>=0||t.indexOf('Pairing code')>=0)return -1;"
            + "var n=0,all=document.querySelectorAll('*');"
            + "for(var i=0;i<all.length;i++){var c=all[i].className;"
            + "if(typeof c==='string'&&(c.indexOf('button')>=0||c.indexOf('widget')>=0))n++;}return n;})()";

    private static final int BG = 0xFF121212;
    private static final int CARD = 0xFF1E1E24;
    private static final int TEXT = 0xFFF5F5F5;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF7C5CFF;

    private WebView web;
    private Button powerToggle;
    private LinearLayout setupPanel;
    private LinearLayout errorPanel;
    private TextView errorText;
    private EditText urlField;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String currentUrl;
    private boolean loaded;
    private long lastBack;
    private long pausedAt;
    private boolean volUp;
    private boolean volDown;
    private boolean purged;
    private int emptyReloads;

    /** Extras envoyés par DeckPowerService. */
    static final String EXTRA_WAKE = "wake";
    static final String EXTRA_SLEEP = "sleep";

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (loaded || currentUrl == null) {
                return;
            }
            if (setupPanel != null && setupPanel.getVisibility() == View.VISIBLE) {
                return;
            }
            web.loadUrl(currentUrl);
            handler.postDelayed(this, RETRY_MS);
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!loaded) {
                fail("pas de réponse du PC");
            }
        }
    };

    // Deck affiché mais vide (grille grise) : on recharge, puis on redémarre l'app.
    private final Runnable deckCheck = new Runnable() {
        @Override
        public void run() {
            web.evaluateJavascript(COUNT_WIDGETS_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    int count = 0;
                    try {
                        count = Integer.parseInt(value == null ? "0" : value.replaceAll("[^0-9]", ""));
                    } catch (NumberFormatException ignored) {
                        count = 0;
                    }
                    if (count < 0 || count > 2) {
                        // < 0 : écran de connexion Macro Deck, rien à recharger.
                        emptyReloads = 0;
                        return;
                    }
                    if (emptyReloads++ < MAX_EMPTY_RELOADS) {
                        purged = false;
                        web.clearCache(true);
                        web.reload();
                    } else {
                        restartApp();
                    }
                }
            });
        }
    };

    private void restartApp() {
        toast("Deck vide : redémarrage de l'app");
        emptyReloads = 0;
        finish();
        startActivity(getIntent());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setKeepScreenOn(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " MacroDeckKiosk/1.0");

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                handler.removeCallbacks(retry);
                handler.removeCallbacks(watchdog);
                hideError();
                if (!purged) {
                    // Premier affichage de la session : on jette les caches applicatifs
                    // hérités d'une mise à jour de profil, puis on recharge une fois.
                    purged = true;
                    web.evaluateJavascript(CLEAR_CACHE_JS, null);
                    web.clearCache(true);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            web.reload();
                        }
                    }, 300);
                    return;
                }
                handler.postDelayed(deckCheck, DECK_CHECK_MS);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    CharSequence desc = error == null ? null : error.getDescription();
                    fail(desc == null ? "connexion impossible" : desc.toString());
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setupPanel = buildSetup();
        errorPanel = buildError();
        root.addView(setupPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(errorPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        errorPanel.setVisibility(View.GONE);

        setContentView(root);
        hideBars();
        startPowerService();
        askNotifications();
        applyPowerIntent(getIntent());
        checkPowerPermissions();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, null);
        urlField.setText(saved == null ? DEFAULT_URL : saved);
        if (saved == null || saved.trim().isEmpty()) {
            // Aucune adresse connue : on affiche la configuration, sinon l'app
            // tenterait de joindre un PC qui n'existe pas sur ce réseau.
            showSetup();
        } else {
            load(saved);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Réseau                                                              */
    /* ------------------------------------------------------------------ */

    private void load(String url) {
        currentUrl = url;
        loaded = false;
        hideError();
        setupPanel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry);
        handler.removeCallbacks(watchdog);
        web.loadUrl(url);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    private void fail(String reason) {
        loaded = false;
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(retry);
        if (errorText != null) {
            errorText.setText("Impossible de joindre le PC.\n\n"
                    + reason + "\n\n"
                    + "Vérifie que Macro Deck tourne sur le PC et que la tablette est sur le même Wi-Fi.\n"
                    + "Nouvel essai automatique toutes les 5 secondes.");
        }
        errorPanel.setVisibility(View.VISIBLE);
        if (currentUrl != null) {
            handler.postDelayed(retry, RETRY_MS);
        }
    }

    private void hideError() {
        if (errorPanel != null) {
            errorPanel.setVisibility(View.GONE);
        }
    }

    private void showSetup() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(watchdog);
        errorPanel.setVisibility(View.GONE);
        if (currentUrl != null && urlField.getText().length() == 0) {
            urlField.setText(currentUrl);
        }
        setupPanel.setVisibility(View.VISIBLE);
        urlField.requestFocus();
    }

    private static String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        int afterScheme = url.indexOf("://") + 3;
        if (url.length() <= afterScheme) {
            return null;
        }
        if (!url.substring(afterScheme).contains(".") && !url.substring(afterScheme).contains(":")) {
            return null;
        }
        return url;
    }

    /* ------------------------------------------------------------------ */
    /* Interface                                                           */
    /* ------------------------------------------------------------------ */

    private LinearLayout panel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(BG);
        box.setPadding(dp(48), dp(32), dp(48), dp(32));
        return box;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private LinearLayout buildSetup() {
        LinearLayout box = panel();
        box.addView(label("Macro Deck", 30, TEXT, true));
        TextView sub = label("Adresse du PC qui fait tourner Macro Deck", 14, MUTED, false);
        box.addView(sub, margin(-2, 8));

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextColor(TEXT);
        urlField.setTextSize(18);
        urlField.setHintTextColor(0xFF6B7280);
        urlField.setHint(URL_EXAMPLE);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setBackground(rounded(CARD, dp(14)));
        urlField.setPadding(dp(18), dp(14), dp(18), dp(14));
        box.addView(urlField, margin(dp(460), 26));

        Button connect = button("Se connecter", ACCENT, Color.WHITE);
        box.addView(connect, margin(dp(420), 20));

        if (!DEFAULT_URL.isEmpty()) {
            Button reset = button("Adresse par défaut", CARD, TEXT);
            box.addView(reset, margin(dp(420), 10));
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    urlField.setText(DEFAULT_URL);
                }
            });
        }

        TextView powerHint = label("Écran lié au PC", 12, MUTED, true);
        box.addView(powerHint, margin(-2, 26));

        powerToggle = button(powerLabel(), CARD, TEXT);
        box.addView(powerToggle, margin(dp(420), 10));
        powerToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePowerLink();
            }
        });

        TextView permHint = label("Autorisations (une seule fois, seulement si activé)",
                12, MUTED, true);
        box.addView(permHint, margin(-2, 18));

        Button wakeGrant = button(overlayOk()
                ? "Allumage auto : autorisé"
                : "Autoriser l'allumage auto", CARD, TEXT);
        box.addView(wakeGrant, margin(dp(420), 10));

        Button offGrant = button(adminOk()
                ? "Extinction auto : activée"
                : "Activer l'extinction auto", CARD, TEXT);
        box.addView(offGrant, margin(dp(420), 10));

        Button battery = button(batteryOk()
                ? "Batterie : sans restriction"
                : "Batterie : aucune restriction", CARD, TEXT);
        box.addView(battery, margin(dp(420), 10));

        TextView hint = label("Port 8193 · Volume + et Volume − en même temps pour revenir ici",
                12, MUTED, false);
        box.addView(hint, margin(-2, 18));

        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = normalize(urlField.getText().toString());
                if (url == null) {
                    toast("Adresse invalide");
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                load(url);
            }
        });
        wakeGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });
        offGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAdminSettings();
            }
        });
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
        return box;
    }

    private LinearLayout buildError() {
        LinearLayout box = panel();
        box.addView(label("PC injoignable", 26, TEXT, true));

        errorText = label("", 14, MUTED, false);
        box.addView(errorText, margin(dp(560), 12));

        Button again = button("Réessayer maintenant", ACCENT, Color.WHITE);
        box.addView(again, margin(dp(380), 22));
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                if (currentUrl != null) {
                    load(currentUrl);
                }
            }
        });

        Button change = button("Changer d'adresse", CARD, TEXT);
        box.addView(change, margin(dp(380), 10));
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSetup();
            }
        });
        return box;
    }

    private Button button(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(bg, dp(14)));
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int width, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /* ------------------------------------------------------------------ */
    /* Écran lié au PC                                                     */
    /* ------------------------------------------------------------------ */

    private String powerLabel() {
        boolean on = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_POWER_LINK, true);
        return on ? "Écran lié au PC : activé" : "Écran lié au PC : désactivé";
    }

    private void togglePowerLink() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean on = !prefs.getBoolean(KEY_POWER_LINK, true);
        prefs.edit().putBoolean(KEY_POWER_LINK, on).apply();
        if (powerToggle != null) {
            powerToggle.setText(powerLabel());
        }
        startPowerService();
        toast(on
                ? "La tablette s'allumera et s'éteindra avec le PC"
                : "La tablette reste allumée en permanence");
    }

    private void startPowerService() {
        Intent service = new Intent(this, DeckPowerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception ignored) {
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    /** Le PC répond de nouveau : écran rallumé, deck rechargé. */
    private void wakeUp() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock show = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "kiosk:activity");
                show.acquire(5000);
            }
        } catch (Exception ignored) {
        }
        keepScreenOn(true);
        DeckPowerService.sleeping = false;
        hideError();
        if (web != null && currentUrl != null) {
            web.reload();
        }
    }

    /** Le PC est éteint : on rend l'écran au système, qui l'éteindra tout seul. */
    private void allowSleep() {
        keepScreenOn(false);
    }

    private void keepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (web != null) {
                web.setKeepScreenOn(true);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (web != null) {
                web.setKeepScreenOn(false);
            }
        }
    }

    private void applyPowerIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.hasExtra(KEY_POWER_LINK)) {
            // Réglage par adb : am start -n …/.MainActivity --ez power_link false
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_POWER_LINK, intent.getBooleanExtra(KEY_POWER_LINK, true))
                    .apply();
            if (powerToggle != null) {
                powerToggle.setText(powerLabel());
            }
            startPowerService();
        }
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) {
            wakeUp();
        } else if (intent.getBooleanExtra(EXTRA_SLEEP, false)) {
            allowSleep();
        }
        intent.removeExtra(EXTRA_WAKE);
        intent.removeExtra(EXTRA_SLEEP);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyPowerIntent(intent);
    }

    private boolean overlayOk() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private boolean adminOk() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(new ComponentName(this, KioskAdmin.class));
    }

    private boolean batteryOk() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Prévient si l'option est active alors qu'une autorisation manque. */
    private void checkPowerPermissions() {
        boolean on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_POWER_LINK, true);
        if (!on || Build.VERSION.SDK_INT < 23) {
            return;
        }
        if (!overlayOk()) {
            // Cas typique : l'appop est réinitialisée à chaque mise à jour de l'APK.
            toast("Écran lié au PC : autorise « Afficher par-dessus les autres applis »");
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
            toast("Déjà autorisé : la tablette peut se rallumer seule");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("Paramètres → Applications → Accès spéciaux");
        }
    }

    private void openAdminSettings() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskAdmin.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            toast("Déjà activé : l'écran s'éteint dès que le PC s'arrête");
            return;
        }
        try {
            startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Autorise l'app à éteindre l'écran quand le PC s'éteint. "
                            + "Aucun effacement de données."));
        } catch (Exception e) {
            toast("Impossible d'ouvrir l'écran d'activation");
        }
    }

    private void openBatterySettings() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("Déjà sans restriction");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("Paramètres → Batterie → Sans restriction");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Plein écran / cycle de vie                                          */
    /* ------------------------------------------------------------------ */

    private void hideBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideBars();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (loaded && pausedAt > 0 && System.currentTimeMillis() - pausedAt > RELOAD_AFTER_MS) {
            web.reload();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
        handler.removeCallbacks(retry);
        handler.removeCallbacks(deckCheck);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ */
    /* Touches                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            toast("Appuie encore sur Retour pour quitter le deck");
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volUp = true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volDown = true;
        }
        if (volUp && volDown) {
            volUp = false;
            volDown = false;
            showSetup();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volUp = false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volDown = false;
        }
        return super.onKeyUp(keyCode, event);
    }
}

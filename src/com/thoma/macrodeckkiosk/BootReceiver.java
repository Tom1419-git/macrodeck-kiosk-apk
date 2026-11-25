package com.thoma.macrodeckkiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Relance le deck automatiquement au démarrage de la tablette.
 *
 * Gère aussi les actions de « démarrage rapide » utilisées par certains
 * constructeurs (Xiaomi/Redmi/POCO, HTC) et la fin d'une mise à jour de l'app.
 *
 * Attention MIUI/HyperOS : sans l'autorisation « Démarrage auto » accordée à
 * l'app, le système bloque ce broadcast. Voir tablet-app/README.md.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launch);
        } catch (Exception ignored) {
            // Système qui refuse un démarrage d'activité en arrière-plan :
            // l'utilisateur peut toujours lancer l'app à la main.
        }
    }
}

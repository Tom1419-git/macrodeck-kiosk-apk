package com.macrodeck.kiosk;

import android.app.admin.DeviceAdminReceiver;

/**
 * Administrateur de l'appareil, utilisé uniquement pour éteindre l'écran quand le PC
 * s'arrête ({@link android.app.admin.DevicePolicyManager#lockNow()}).
 *
 * Sans cette autorisation, la tablette s'éteint quand même mais seulement au bout du
 * délai de veille du système.
 */
public class KioskAdmin extends DeviceAdminReceiver {
}

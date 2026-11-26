# Installer Macro Deck Kiosk avec un agent IA

Ce document contient un **prompt prêt à coller** dans un agent de codage ou un assistant
qui a accès à un terminal (Freebuff, Claude Code, Codex, Cursor…). L'agent installe l'APK
sur la tablette, configure les autorisations et vérifie que le deck s'affiche.

Le prompt suppose que :

- la tablette est sous **Android 7+**, sur le **même Wi-Fi** que le PC qui fait tourner
  **Macro Deck 3** ;
- tu as un câble USB, ou le **débogage sans fil** activé sur la tablette ;
- tu connais l'**adresse du PC** (par exemple `192.168.1.50`) et le port de Macro Deck
  (par défaut `8193`).

## Le prompt

```text
Tu es un assistant d'installation. Objectif : installer l'APK « Macro Deck Kiosk »
(https://github.com/Tom1419-git/macrodeck-kiosk-apk) sur ma tablette Android et vérifier
qu'il fonctionne comme kiosque plein écran du web client de Macro Deck 3.

Contexte à me demander avant de commencer (et à ne pas deviner) :
- l'adresse IPv4 du PC qui fait tourner Macro Deck 3 (port 8193 par défaut) ;
- si la tablette est connectée en USB ou en débogage sans fil ;
- le code d'appairage à 6 chiffres affiché dans Macro Deck (panneau Réseau) : tu me le
  demanderas au moment de la première connexion, jamais avant.

Règles :
- ne supprime aucune donnée de la tablette, ne flashe rien, ne root rien ;
- toute commande destructive ou inhabituelle doit m'être expliquée avant d'être lancée ;
- si une étape échoue, affiche la sortie exacte de la commande et propose une correction,
  sans passer à l'étape suivante ;
- à la fin, donne un récapitulatif : version installée, autorisations accordées, résultat
  du test d'allumage/extinction.

Étapes attendues :

1. Préparer adb
   - Vérifier `adb devices` ; si la tablette n'apparaît pas, m'expliquer comment activer
     « Options pour les développeurs » puis « Débogage USB » (7 taps sur Numéro de build).
   - En sans-fil (Android 11+) : « Débogage sans fil » → « Associer avec un code » →
     `adb pair <ip>:<port>` puis `adb connect <ip>:<port>` (le port change à chaque
     redémarrage de la tablette).
   - Si `adb devices` affiche `unauthorized`, me demander de valider la fenêtre
     « Autoriser le débogage USB » sur la tablette.

2. Installer l'APK
   - Télécharger la dernière release :
     `curl -L -o MacroDeckKiosk.apk https://github.com/Tom1419-git/macrodeck-kiosk-apk/releases/latest/download/MacroDeckKiosk.apk`
   - `adb install -r MacroDeckKiosk.apk` (si MIUI refuse, utiliser
     `adb push MacroDeckKiosk.apk /data/local/tmp/` puis
     `adb shell pm install -r -d /data/local/tmp/MacroDeckKiosk.apk`).
   - Vérifier : `adb shell dumpsys package com.thoma.macrodeckkiosk | grep versionName`.

3. Configurer l'adresse du PC
   - Lancer l'app : `adb shell am start -n com.thoma.macrodeckkiosk/.MainActivity`.
   - L'adresse se saisit sur la tablette (champ « Adresse du PC », ex. 192.168.1.50:8193).
     Si l'écran de configuration n'est pas visible, m'indiquer d'appuyer simultanément sur
     Volume + et Volume −.
   - Première connexion : Macro Deck affiche « Sign in to continue » ; me demander de lire
     le code d'appairage à 6 chiffres dans le panneau Réseau du PC et de le saisir sur la
     tablette.

4. Autorisations (une seule fois, nécessaires à l'option « écran lié au PC »)
   - Notifications : `adb shell pm grant com.thoma.macrodeckkiosk android.permission.POST_NOTIFICATIONS`
   - Rallumage de l'écran : `adb shell appops set com.thoma.macrodeckkiosk SYSTEM_ALERT_WINDOW allow`
     (ou sur la tablette : Paramètres → Applications → Accès spéciaux → Afficher par-dessus
     les autres applis).
   - Extinction immédiate : `adb shell dpm set-active-admin com.thoma.macrodeckkiosk/.KioskAdmin`
     (si la commande refuse, m'indiquer d'utiliser le bouton « Activer l'extinction auto »
     de l'écran d'adresse — l'app n'utilise que la politique de verrouillage d'écran).
   - Batterie : `adb shell dumpsys deviceidle whitelist +com.thoma.macrodeckkiosk`, et sur
     MIUI/HyperOS m'indiquer d'activer « Démarrage auto » (Paramètres → Applications →
     Gérer les applications → Macro Deck) et Batterie → Aucune restriction.

5. Vérifier
   - `adb shell dumpsys window | grep mCurrentFocus` doit contenir
     `com.thoma.macrodeckkiosk`.
   - `adb shell dumpsys power | grep mWakefulness` doit afficher `Awake`.
   - Test du lien avec le PC : couper le Wi-Fi de la tablette
     (`adb shell svc wifi disable`), attendre ~30 s, l'écran doit passer `Asleep` ;
     réactiver (`adb shell svc wifi enable`), attendre ~25 s, l'écran doit revenir
     `Awake` avec l'app au premier plan. Remettre le Wi-Fi en état à la fin.
   - Si l'option doit rester désactivée : `adb shell am start
     -n com.thoma.macrodeckkiosk/.MainActivity --ez power_link false`.

6. Récapituler et me dire quoi vérifier à la main (rendu du deck, icônes, démarrage
   automatique de la tablette).
```

## Ce que le prompt ne peut pas faire

- **Saisir le code d'appairage** : il faut lire le code dans Macro Deck et le taper sur la
  tablette (ou donner le code à l'agent, qui peut le saisir par `adb shell input text`,
  mais c'est un secret de session — à éviter dans un historique de conversation).
- **Accorder certaines autorisations** : MIUI/HyperOS exige parfois une confirmation
  manuelle (« Démarrage auto », « Aucune restriction »).
- **Piloter le PC** : le réveil de la tablette dépend uniquement du fait que Macro Deck
  répond sur le réseau. Si Macro Deck ne démarre pas avec Windows, la tablette ne se
  rallumera pas : vérifier le raccourci de démarrage automatique sur le PC.

## Installation manuelle (sans agent)

1. Télécharger `MacroDeckKiosk.apk` depuis les [releases](https://github.com/Tom1419-git/macrodeck-kiosk-apk/releases).
2. L'ouvrir sur la tablette (Fichiers → Téléchargements), autoriser « Installer des
   applis inconnues ».
3. Saisir l'adresse du PC au premier lancement, puis le code d'appairage de Macro Deck.
4. Sur l'écran d'adresse (`Volume +` + `Volume −`), appuyer sur les trois boutons
   d'autorisation si l'on veut que la tablette s'allume et s'éteigne avec le PC.

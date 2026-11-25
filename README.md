# Macro Deck Kiosk — APK plein écran pour tablette Android

Petite application Android (≈ 34 Ko, aucune dépendance) qui affiche le **web client de
Macro Deck 3** en vrai plein écran sur une tablette : pas de barre d'adresse, pas de
barre système, pas d'effet « site web ». Pratique pour transformer une vieille tablette
en deck de touches mural, sans passer par l'application payante.

`MacroDeckKiosk.apk` est fourni **compilé et signé** à la racine du dépôt : il suffit de
l'installer, aucun outil de développement n'est nécessaire.

## Ce qu'elle fait

- **Plein écran immersif** : barres Android masquées, elles ne reviennent qu'en glissant
  depuis le bord (et disparaissent aussitôt).
- **Écran toujours allumé** (`FLAG_KEEP_SCREEN_ON`) : la tablette ne s'éteint pas pendant
  que le deck est affiché.
- **Reconnexion automatique** : si le PC est éteint ou redémarre, un écran « PC
  injoignable » s'affiche et retente toutes les 5 s ; le deck revient tout seul.
- **Démarrage automatique** avec la tablette (récepteur `BOOT_COMPLETED`, plus les
  actions « démarrage rapide » de certains constructeurs et la fin d'une mise à jour).
- **Adresse mémorisée** : elle se saisit une seule fois, au premier lancement.
- **Paysage**, **retour = 2 appuis** pour quitter (avec un toast de confirmation).
- **Reconfiguration sans câble** : `Volume +` et `Volume −` en même temps ramène à
  l'écran d'adresse.
- Le WebView parle HTTP en clair vers le réseau local (config réseau dédiée) et accepte
  la lecture audio sans geste utilisateur (widgets Music Player).

## Installation

1. Télécharger **`MacroDeckKiosk.apk`** (bouton *Download* / *Raw* sur la page du
   fichier, ou depuis une release).
2. Sur la tablette, ouvrir le fichier depuis **Fichiers → Téléchargements** et autoriser
   « Installer des applis inconnues » pour l'app Fichiers si demandé.
3. Lancer **Macro Deck**, saisir l'adresse du PC qui fait tourner Macro Deck
   (par exemple `192.168.1.50:8193`, le port du web client est **8193**), puis
   **Se connecter**.

Sur la tablette, tout ce que fait le web client (musique, lumières, volume, navigation)
fonctionne à l'identique : l'APK n'est qu'une coquille plein écran autour du même deck.

## Première connexion (une seule fois)

Le web client de Macro Deck 3 exige une session : au premier lancement, la tablette
affiche **« Sign in to continue »**. Sur le PC, ouvrir le panneau **Réseau** (icône Wi-Fi
en haut à droite) de Macro Deck et saisir le **code d'appairage** à 6 chiffres sur la
tablette. La session est stockée dans les données de l'app : elle survit aux mises à
jour mais pas à une désinstallation.

## Démarrage automatique

L'app se relance seule au démarrage de la tablette.

**Sur Xiaomi / Redmi / POCO (MIUI, HyperOS), une autorisation est obligatoire**, sinon le
système bloque le broadcast de démarrage :

1. Paramètres → Applications → Gérer les applications → **Macro Deck**
2. **Démarrage auto** → activer
3. (recommandé) Batterie → **Aucune restriction**

Sur d'autres marques, rien à faire au-delà de l'installation. Android 10+ peut aussi
demander « Autoriser le démarrage en arrière-plan » : accepter.

## Changer l'adresse du PC

`Volume +` et `Volume −` en même temps sur la tablette, ou bouton **Changer d'adresse**
depuis l'écran « PC injoignable ». Rien à recompiler.

## Dépannage

- **Grille grise sans icônes au premier lancement** : le WebView n'a pas encore chargé le
  pack d'icônes. L'app recharge puis se relance d'elle-même ; sinon redémarrer la
  tablette une fois la connexion établie.
- **« PC injoignable » en boucle** : vérifier que Macro Deck tourne, que le PC et la
  tablette sont sur le même Wi-Fi, et que le pare-feu Windows autorise Macro Deck sur le
  réseau privé. Tester l'adresse depuis un navigateur de la tablette.
- **La tablette ne relance pas le deck après un redémarrage** : autorisation
  « Démarrage auto » non accordée (voir plus haut).
- **Mise à jour refusée (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)** : l'APK installé a été
  signé avec une autre clé. Désinstaller l'ancienne version avant d'installer celle-ci.

## Contenu du dépôt

```
AndroidManifest.xml              manifeste (min SDK 24, target 34)
src/com/thoma/macrodeckkiosk/    MainActivity.java, BootReceiver.java
res/                             icônes, thème, config réseau
tools/make_icons.py              régénère les icônes (Pillow)
build.sh                         compilation + signature, sans Gradle
MacroDeckKiosk.apk               APK signé, prêt à installer
```

## Recompiler

Prérequis : un **JDK 17+** (`JAVA_HOME`) et un **SDK Android** avec les *build-tools 35*
et la plateforme *android-34*.

```bash
ANDROID_SDK=/chemin/vers/android-sdk bash build.sh   # → MacroDeckKiosk.apk
```

Pas de Gradle : `aapt2` + `javac` + `d8` + `zipalign` + `apksigner`. La clé de signature
est créée au premier build dans `keystore/` (dossier ignoré par git) : conserve-la si tu
veux publier des mises à jour installables par-dessus l'ancienne version.

## Notes

- Aucune configuration personnelle n'est incluse dans ce dépôt : ni adresse de PC, ni
  profil de deck, ni identifiant. L'adresse se saisit sur la tablette au premier
  lancement.
- Développé et testé sur une Xiaomi Pad 6 (HyperOS, Android 14) ; fonctionne sur toute
  tablette Android 7.0+ en paysage.

---

## Crédits

Ce projet a été conçu et écrit en **pair-programmation avec une IA** : [Buffy](https://freebuff.com),
l'agent de codage de Freebuff, a participé au code Java, aux ressources, aux icônes, au
script de compilation et à cette documentation, sous la direction de **Tom1419-git**, qui
maintient le dépôt et en valide chaque version.

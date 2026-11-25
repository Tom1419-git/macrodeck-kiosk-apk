#!/usr/bin/env bash
# Compile l'APK kiosque sans Gradle : aapt2 + javac + d8 + zipalign + apksigner.
#
#   ANDROID_SDK=/chemin/vers/android-sdk bash build.sh
#
# Prérequis : un JDK 17 ou plus (JAVA_HOME) et un SDK Android avec
# build-tools 35 et la plateforme android-34.
set -euo pipefail

cd "$(dirname "$0")"

# --- SDK Android ------------------------------------------------------------
SDK="${ANDROID_SDK:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    for candidate in "$HOME/AppData/Local/Android/Sdk" "$HOME/Library/Android/sdk" \
                     "$HOME/Android/Sdk" "/usr/lib/android-sdk"; do
        if [ -d "$candidate" ]; then SDK="$candidate"; break; fi
    done
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "SDK Android introuvable : définis ANDROID_SDK=/chemin/vers/android-sdk" >&2
    exit 1
fi

# build-tools 35 : le d8 de la 34 plante sur les classes anonymes (R8 8.2.2).
TOOLS="${BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
if [ ! -d "$TOOLS" ]; then
    TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
fi
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
    ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
if [ ! -f "$ANDROID_JAR" ]; then
    echo "android.jar absent : installe une plateforme dans $SDK/platforms" >&2
    exit 1
fi

# Les outils Android sont .exe/.bat sous Windows, sans extension sous Linux/macOS.
tool() {
    if [ -f "$1.exe" ]; then echo "$1.exe"
    elif [ -f "$1.bat" ]; then echo "$1.bat"
    else echo "$1"; fi
}
AAPT2=$(tool "$TOOLS/aapt2")
ZIPALIGN=$(tool "$TOOLS/zipalign")
APKSIGNER=$(tool "$TOOLS/apksigner")

# --- JDK --------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    for candidate in "/c/Program Files/Java/jdk-21.0.12" "/c/Program Files/Java/jdk-21"/* \
                     "/c/Program Files/Java/jdk-17"/* "/usr/lib/jvm"/*; do
        if [ -x "$candidate/bin/javac" ]; then export JAVA_HOME="$candidate"; break; fi
    done
fi
if [ -x "${JAVA_HOME:-}/bin/javac" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v javac >/dev/null || { echo "javac absent : installe un JDK 17+" >&2; exit 1; }

echo "1/7 icônes"
python tools/make_icons.py 2>/dev/null || echo "   (Pillow absent : icônes déjà présentes dans res/)"

echo "2/7 ressources (aapt2 compile)"
rm -rf build
mkdir -p build/gen build/classes build/dex
"$AAPT2" compile --dir res -o build/res.zip

echo "3/7 manifeste (aapt2 link)"
"$AAPT2" link \
    -o build/app-unsigned.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    build/res.zip

echo "4/7 compilation Java"
javac -encoding UTF-8 -source 17 -target 17 -Xlint:-options -nowarn \
    -classpath "$ANDROID_JAR" \
    -d build/classes \
    $(find build/gen src -name '*.java')

echo "5/7 dex (d8)"
find build/classes -name '*.class' > build/args.txt
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 24 --lib "$ANDROID_JAR" \
    --output build/dex @build/args.txt

python - <<'PY'
import shutil
import zipfile

shutil.copy("build/app-unsigned.apk", "build/with-dex.apk")
with zipfile.ZipFile("build/with-dex.apk", "a", zipfile.ZIP_STORED) as apk:
    apk.write("build/dex/classes.dex", "classes.dex")
print("classes.dex ajouté")
PY

echo "6/7 alignement + signature"
"$ZIPALIGN" -f -p 4 build/with-dex.apk build/app-aligned.apk
# Clé de signature persistante : hors de build/, sinon chaque compilation en
# génère une nouvelle et les mises à jour sont refusées (signatures différentes).
# Ce dossier est ignoré par git : chaque personne qui recompile utilise sa clé.
KS="$(pwd)/keystore/kiosk.keystore"
mkdir -p keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -v \
        -keystore "$KS" -alias kiosk \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass kioskpass -keypass kioskpass \
        -dname "CN=Macro Deck Kiosk, O=Kiosk, C=FR" >/dev/null 2>&1
    echo "nouvelle clé de signature : keystore/kiosk.keystore (à conserver)"
fi
"$APKSIGNER" sign \
    --ks "$KS" \
    --ks-pass pass:kioskpass --key-pass pass:kioskpass \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out MacroDeckKiosk.apk build/app-aligned.apk

echo "7/7 vérification"
"$APKSIGNER" verify --print-certs MacroDeckKiosk.apk | head -4
"$AAPT2" dump badging MacroDeckKiosk.apk | head -5
ls -la MacroDeckKiosk.apk

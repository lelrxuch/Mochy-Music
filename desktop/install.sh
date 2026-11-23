#!/usr/bin/env bash
# Builds Mochy Desktop and installs it for the current user only (no sudo):
# the app folder goes to ~/Applications/mochy-desktop and its launcher to the GNOME app menu.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew -p desktop createDistributable --console=plain -q

dest="$HOME/Applications/mochy-desktop"
rm -rf "$dest"
mkdir -p "$HOME/Applications" "$HOME/.local/share/applications"
cp -r desktop/build/compose/binaries/main/app/mochy-desktop "$dest"

cat > "$HOME/.local/share/applications/mochy-desktop.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Mochy
Comment=Mochy Music para Linux
Exec=$dest/bin/mochy-desktop
Icon=$dest/lib/mochy-desktop.png
Categories=AudioVideo;Audio;Player;
StartupWMClass=com-mochyy-desktop-MainKt
EOF

command -v mpv >/dev/null || echo "Aviso: falta mpv para reproducir. Instálalo con: sudo dnf install mpv"
echo "Instalado en $dest"

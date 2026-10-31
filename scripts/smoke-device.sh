#!/usr/bin/env bash
# Smoke test on a dual-screen device: installs the APK and captures key screens.
#
# Uso:  HUGINN_BOT_DISPLAY=<id> HUGINN_TOP_DISPLAY=<id> bash scripts/smoke-device.sh [directorio-salida]
# Requiere: dispositivo por depuración inalámbrica (.tmp/adb-target) y
#           .secrets/smb.properties subido con scripts/push-smb-config.sh.
# Los ids únicos de las pantallas cambian por unidad: se obtienen con
#   adb shell dumpsys display | grep -E 'uniqueId|DisplayDeviceInfo' 
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV="$ROOT/scripts/device.sh"
PKG="dev.huginn.debug"
OUT="${1:-$ROOT/.tmp/smoke}"
BOT="${HUGINN_BOT_DISPLAY:?define HUGINN_BOT_DISPLAY with the lower display unique id}"
TOP="${HUGINN_TOP_DISPLAY:?define HUGINN_TOP_DISPLAY with the upper display unique id}"
mkdir -p "$OUT"

shot_bot() { "$DEV" exec-out screencap -p -d "$BOT" > "$OUT/$1.png"; }
shot_top() { "$DEV" exec-out screencap -p -d "$TOP" > "$OUT/$1.png"; }
stop_app() { "$DEV" shell am force-stop "$PKG" >/dev/null 2>&1; sleep 1; }
launch()  { stop_app; "$DEV" shell am start -n "$PKG/dev.huginn.MainActivity" "$@" >/dev/null 2>&1; sleep 9; }
keys_bot() {
  local ok=1
  for _ in 1 2 3; do
    if "$DEV" shell "input -d 4 keyevent $1" >/dev/null 2>&1; then ok=0; break; fi
    sleep 1
  done
  [ "$ok" -eq 0 ] || echo "AVISO: no se pudo inyectar $1" >&2
  sleep 1
}
shot_both() { shot_bot "$1-bottom"; shot_top "$1-top"; }

echo "== Instalando APK =="
"$DEV" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1

echo "== 1) Raíz =="
launch
shot_both 01-raiz

echo "== 2) SMB, lista =="
launch --ez open_smb true --es view LIST
shot_both 02-smb-lista

echo "== 3) SMB, grid =="
launch --es open_path "smb://192.0.2.10/Data/Media/" --es view GRID
shot_both 03-smb-grid

echo "== 4) SMB, galería (carpeta solo con carpetas) =="
launch --es open_path "smb://192.0.2.10/Data/Media/" --es view GALLERY
shot_both 04-smb-galeria

echo "== 5) Menú (extras, sin inyectar teclas) =="
launch --ez open_smb true --ez open_menu true --es view LIST
shot_bot 05-menu

echo "== 6) Orden por fecha descendente =="
launch --ez open_smb true --es sort DATE --ez sort_desc true --es view LIST
shot_bot 06-orden-fecha

echo "== 7) Vídeo: controles fijos (determinista) =="
launch --ez test_video true --ez keep_controls true
shot_top 07-video-controles

echo "== 8) Vídeo: controles autoocultos a los 3 s =="
launch --ez test_video true
sleep 5
shot_top 08-video-sin-controles

echo "== 9) Menú de pistas (extras) =="
launch --ez test_video true --ez keep_controls true --ez track_menu true
sleep 3
shot_top 09-video-pistas

echo "== 10) Visor de imagen =="
launch --ez test_image true
sleep 2
shot_top 10-imagen

echo "== 11) Galería con imágenes reales (SMB) =="
launch --es open_path "smb://192.0.2.10/Data/Media/Images/Sample/" --es view GALLERY
sleep 12
shot_bot 11-galeria-imagenes
launch --es open_path "smb://192.0.2.10/Data/Media/Images/Sample/" --es view GALLERY --ei select 2
sleep 20
shot_top 11b-ficha-imagen

echo "== 12) Audio SMB con letra .lrc (ruta con caracteres raros) =="
launch --es open_path "smb://192.0.2.10/share/Media/Music/Sample-Album/" --ez play_first true
sleep 15
shot_top 12-audio-letra

echo "== 13) Vídeo SMB =="
launch --es open_path "smb://192.0.2.10/Data/Media/Videos/" --ez play_first true --ez keep_controls true
sleep 12
shot_top 13-video-smb

echo "== 14) Ficha compacta de carpeta =="
launch --ez open_smb true --ei select 1 --es view LIST
shot_top 14-ficha-carpeta

echo "== 15) Carátula de audio SMB =="
launch --es open_path "smb://192.0.2.10/share/Media/Music/Sample-Album/" --ez play_first true
sleep 14
shot_top 15-carátula-audio

echo "== 16) Pantalla inferior apagada durante vídeo =="
launch --ez test_video true --ez screen_off true
sleep 2
shot_bot 16-pantalla-apagada

echo "== Fin. Capturas en $OUT =="
ls -la "$OUT"

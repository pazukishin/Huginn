#!/usr/bin/env bash
# Sube la configuración SMB de desarrollo al almacén privado de la app EN EL
# DISPOSITIVO, sin imprimir la contraseña, y relanza Huginn.
#
# Entrada: .secrets/smb.properties (gitignored, chmod 600). Formato:
#   host=192.0.2.10
#   share=media
#   user=usuario
#   password=...
#   domain=WORKGROUP   (opcional)
#   smb1=false         (opcional)
#
# La contraseña se envía por stdin (`cat >`) y nunca aparece en la línea de
# comandos ni en la salida de este script.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="$ROOT/.secrets/smb.properties"
if [ "${1:-}" = "--config" ] && [ -n "${2:-}" ]; then
  CONFIG="$2"
fi
PKG="dev.huginn.debug"
DEVICE="$ROOT/scripts/device.sh"

if [ "${1:-}" = "--remove" ]; then
  "$DEVICE" shell "run-as $PKG sh -c 'rm -f files/smb-dev.properties'" >/dev/null 2>&1 || true
  echo "Configuración SMB de desarrollo eliminada del dispositivo."
  exit 0
fi

if [ ! -f "$CONFIG" ]; then
  echo "ERROR: falta $CONFIG" >&2
  echo "Créalo con el comando que te pasó el agente (read -s + printf)." >&2
  exit 1
fi
if [ "$(stat -c '%a' "$CONFIG")" != "600" ]; then
  echo "AVISO: $CONFIG debería tener permisos 600 (chmod 600)." >&2
fi

echo "== Parando la app =="
"$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1 || true

echo "== Subiendo configuración al almacén privado de la app (stdin, sin eco) =="
"$DEVICE" shell "run-as $PKG sh -c 'mkdir -p files && cat > files/smb-dev.properties && chmod 600 files/smb-dev.properties'" < "$CONFIG"

echo "== Comprobando fichero en el dispositivo =="
"$DEVICE" shell "run-as $PKG sh -c 'ls -l files/smb-dev.properties'"

echo "== Relanzando Huginn =="
"$DEVICE" shell am start -n "$PKG/dev.huginn.MainActivity" >/dev/null
echo "Listo. El contenido del fichero NO se ha mostrado."

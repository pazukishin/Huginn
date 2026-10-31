# Validación de M3 (SMB) en la AYN Thor

Fecha: 30-oct-2026. Complementa `docs/testing-m1-m2.md` (M3 añade servidores,
credenciales cifradas, shares, errores/reintento y SMB1 opt-in).

## Automático

- `bash scripts/device.sh` + `./gradlew :app:testDebugUnitTest :app:lintDebug`.
- **88 tests verdes**, `lint` sin errores. Cubren el codec/store de servidores,
  conservación del criptograma al editar, traducción de errores SMB y el retry.

## Pasada en la Thor (evidencias en `.tmp/m3/`, no versionadas)

1. **Importación cifrada**: la config de desarrollo se convierte en el servidor
   «NAS (importado)»; la contraseña queda AES-GCM en `files/smb-servers.properties`
   sin texto plano (comprobado con `run-as … cat` y `grep` del texto plano: 0).
2. **Raíz multiserver**: la raíz lista los servidores guardados (host · share ·
   usuario) y al abrirlos se fija la sesión activa y se lista el share `Data`
   (12 elementos).
3. **Alta desde la UI**: formulario en la **pantalla superior** (la `Presentation`
   inferior no es focusable, el IME solo funciona en la ventana de la Activity).
   Campos: nombre, host, share, usuario, dominio, contraseña y **SMB1**. Verificado:
   el fichero persistido contiene el criptograma y `grep` de la contraseña en claro
   devuelve 0.
4. **Edición conserva la contraseña**: cambiar el share y guardar deja el mismo
   criptograma (regresión corregida: antes el upsert la borraba). Test unitario
   `mergePreservingSecret`.
5. **Borrado en dos pasos**: «¿Seguro? Pulsa para borrar» y desaparece de la lista
   (y del fichero). Plural del subtítulo correcto («1 servidor»).
6. **Shares**: servidor sin share → se listan `Data`, `IPC$`, `personal_folder`.
7. **Errores de red**: servidor con host `no-existe.local` → «No se encuentra el
   servidor (revisa el host)», botón **Reintentar (A)**, y log
   `fallo SMB reintentable; contexto nuevo y reintento` (reintento con contexto
   jcifs nuevo). Cada intento ~12 s (resolución DNS con dominios de búsqueda).
8. **Credenciales incorrectas**: usuario inexistente → «Usuario o contraseña
   incorrectos» en ~0,2 s y **sin reintento** (no reintentable).
9. **Salida del error**: `B` vuelve a la raíz tanto con el error en pantalla como
   mientras reintenta (antes podía cerrar la app desde una ubicación sin migas).
10. **Reproducción `smb://` con la sesión M3**: abrir servidor → `Data/Media/…` →
    primera película reproducida en la pantalla superior sin descargar.
11. **Crash corregido**: registrar el callback de red en `AppContainer` durante su
    construcción provocaba NPE (`smbSource` aún no inicializado) al disparar
    `onAvailable`; ahora se registra después de crear `smbSource` (validado: la app
    arranca y navega).

## Caída de red real (30-oct-2026) — superada

Prueba coordinada: app abierta en `smb://192.0.2.10/Data/` (12 elementos),
Wi-Fi de la Thor apagado y vuelto a encender (se corta adb y cambia el puerto de
depuración inalámbrica: **39803 → 35773**).

Resultado:

- La app **sobrevivió** al corte (mismo proceso) y la pantalla inferior mantuvo el
  listado.
- Al recuperar la red y pulsar refrescar, `SmbSource` **creó un contexto jcifs
  nuevo** (`HuginnSmb: contexto SMB creado para 192.0.2.10/Data`) y el listado se
  repintó sin error ni reinicio de la app.
- No hizo falta el retry manual: el callback de red invalidó el contexto cacheado al
  volver la conectividad (diseño de M3) y la siguiente operación reconectó sola.

## Limitaciones / pendiente

- **SMB1 con servidor real**: no hay ningún servidor SMB1 en la red. El opt-in está
  en la UI y aplica `jcifs.smb.client.minVersion=SMB1` (cubierto con tests unitarios
  `SmbJcifsPropertiesTest`); además se comprobó en la Thor que activarlo **no rompe**
  conexiones SMB2/3 (el NAS sigue listando). Falta la validación positiva con un
  servidor que solo hable SMB1; **M3 se cierra con esta limitación documentada**.

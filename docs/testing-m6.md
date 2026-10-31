# Validación de M6 (pruebas y release) — Huginn 1.0.0

Fecha: 31-oct-2026. Cierra la matriz de pruebas de v1.

## Automático (host)

| Prueba | Resultado |
|---|---|
| `:app:testDebugUnitTest` | **128 tests verdes** (dominio puro: red SMB, caché de metadatos, miniaturas, búsqueda, recientes/favoritos, displays, mapeo de teclas, codecs) |
| `:app:lintDebug` | sin errores |
| `:app:assembleRelease` | APK firmado (`app-release.apk`, ~90 MB) |
| `apksigner verify --print-certs` | firmante `CN=Huginn, OU=Huginn, O=Huginn, C=ES` (keystore propio) |
| `versionName/versionCode` | `1.0.0` / `1` |

## Firma (importante)

- Keystore: `.secrets/huginn-release.jks` + `.secrets/release-signing.properties`
  (ambos **ignorados por git**; permisos 600).
- `app/build.gradle.kts` carga esas propiedades; si no existen, cae a la firma de debug
  para poder compilar en CI sin secretos.
- **Guardar copia de seguridad del keystore y la contraseña**: perdiéndolo no se pueden
  publicar actualizaciones de la app.

## Dispositivo (AYN Thor, Android 13)

- **Idioma**: por defecto inglés; Ajustes → Idioma → Español cambia toda la UI
  (raíz, ajustes, barra inferior, ficha) y persiste tras recrear la activity;
  volver a English funciona igual. Evidencias: `.tmp/m6/06-en-root.png`,
  `.tmp/m6/09-es-language.png`.
- **Sin UI de depuración**: no está la barra superior de diagnóstico
  (`top=0 · expl=4…`), ni la pista «tecla: …», ni las entradas de prueba de la raíz.
- **Ajustes**: limpiar caché, servidores SMB, mapeo de botones, idioma y acerca de.
- **APK release**: instalado en la Thor (`dev.huginn`, versión 1.0.0) y arranca en
  inglés sin elementos de depuración; se desinstaló después de la prueba para dejar el
  dispositivo como estaba (la app debug `dev.huginn.debug` sigue instalada).
- **Regresión funcional** (ya validada en M5 y reutilizada aquí): recientes/favoritos,
  búsqueda local, mapeo de botones con aprendizaje, apertura externa arriba y selector
  «Abrir con», pósteres y metadatos persistentes.

## Limitaciones conocidas de v1

- Solo lectura (sin crear/borrar/renombrar).
- El contenido se queda en la pantalla donde se abre la app; moverlo no está
  soportado (la `Presentation` falla al relanzar la activity en el otro display).
- PDF: no validado con un visor real en la Thor (solo una app de terceros declara
  `application/pdf` y se descartó por seguridad); se presupone correcto con un visor
  instalado.
- Búsqueda local: solo carpeta SAF (o la ubicación actual si es local); límites de
  profundidad 6, 5.000 entradas y 200 resultados.
- SMB1: el opt-in existe y está probado en unit tests, pero no hay servidor SMB1-only
  para validación positiva real.
- 5.000 entradas cubiertas por tests JVM; en dispositivo se probó una carpeta SMB de
  561 elementos sin ANR.

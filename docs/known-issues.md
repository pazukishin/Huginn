# Problemas conocidos y próximos pasos — Huginn

Actualizado: 30-oct-2026 (tarde — cierre de M2 validado en la Thor).

## RESUELTO — audio se perdía para el resto de la sesión tras cerrar/cambiar

**Causa raíz (30-oct-2026):** `MediaPlayer.stop()` de libVLC en la Thor deja el
audio output inservible para la siguiente reproducción (no vuelve a sonar hasta
reiniciar la app). Al pulsar atrás se cerraba el reproductor con `stop()` y desde
ese momento todo lo demás sonaba mudo.

**Solución:** el cierre de reproducción **pausa** en vez de parar
(`player.pause()` + reset de estado). Confirmado por el usuario: atrás para el
audio y navega a la carpeta anterior, y la siguiente canción vuelve a sonar.

Se mantiene la nota histórica con la evidencia:

### Nota histórica — audio se pierde al volver a reproducir / ciertos casos

**Estado (histórico, ya resuelto):** con `--aout=opensles` el audio sonaba la
primera vez y al cambiar de canción, pero se perdía al repetir/parar. El análisis
de estos logs llevó a la causa raíz de arriba (`stop()` rompe el aout).

**Evidencia recogida (logcat, mismo proceso, misma canción):**

```
23:56:25  HuginnVlc: reproduciendo …/01. 螺旋 - RASEN.flac     ← suena
23:56:39  HuginnVlc: reproduciendo …/01. 螺旋 - RASEN.flac     ← replay
23:56:39  E VLC: libvlc audio output: AudioTrack.DynamicsProcessing.ctor triggered an exception !
23:56:45  HuginnVlc: reproduciendo …/01. 螺旋 - RASEN.flac     ← replay otra vez
23:56:46  E VLC: libvlc audio output: AudioTrack.DynamicsProcessing.ctor triggered an exception !
```

Notas:
- `AudioTrack.DynamicsProcessing` es del módulo **android_audiotrack**, no de
  `opensles_android`; al repetir la reproducción libVLC parece volver al módulo
  audiotrack (o el aout se reinicializa mal) y queda mudo.
- `dumpsys audio`: STREAM_MUSIC sin mute, volumen 13/15, speaker; foco de audio
  concedido en cada play.
- La primera reproducción tras abrir la app sí suena (opensles).

**Hipótesis y experimentos planeados:**

1. **Replay del mismo medio con el MediaPlayer reutilizado**: probar
   `if (player.hasMedia) player.stop()` **antes** de `setMedia` (solo si hay medio;
   en la primera reproducción no parar nada). Variante a medir con logcat +
   confirmación auditiva.
2. **Recrear `MediaPlayer`** para cada reproducción (mantener un `LibVLC` compartido,
   re-enganchar la `SurfaceView` del visor). Elimina por completo el estado corrupto
   del aout; coste: reattach de superficie en vídeo.
3. **Forzar el módulo tras cada parada**: `player.setAudioOutput("opensles")` al
   empezar cada reproducción (API de libVLC) o comprobar `tracks()` para detectar
   aout muerto.
4. **libVLC 4.0 EAP** (`4.0.0-eap29`) para ver si el bug de aout está corregido.
5. Como plan B de producto: **Media3/ExoPlayer para audio** (y dejar libVLC solo
   para vídeo), que era el plan si el tamaño del APK pasaba a segundo plano.

**Cómo reproducir y validar (con el usuario delante):**

```bash
# misma canción dos veces seguidas
adb shell am start -n dev.huginn.debug/dev.huginn.MainActivity \
  --es open_path "smb://192.0.2.10/Data/Media/Music/Sample%20Share/" --ez play_first true
# y pulsar A otra vez sobre el mismo elemento (o usar el extra dev de ciclo)
```

Capturar siempre: `logcat -s HuginnVlc VLC` (buscar «using audio output module» y
«DynamicsProcessing») y `dumpsys audio` (estado del AudioTrack de la app).

## Otros pendientes menores

- **FUERA DE v1 — apertura desde el dock de un launcher de terceros:** si Huginn se
  abre desde la barra de apps de la pantalla inferior de otro launcher (su dock), la
  ventana superior no se muestra. Causa probable: el dock lanza `MainActivity` con
  `launchDisplayId=4` (inferior); con la arquitectura actual `MainActivity` es el
  *contenido*, así que acaba en el display 4 y el display 0 queda con el launcher.
  Arreglo previsto (~5 líneas): en `MainActivity.onCreate`, si `currentDisplayId()`
  no es `topology.contentDisplayId`, relanzarse a `contentDisplayId` (lo inverso del
  antiguo `relaunchOnDisplay`). **Decisión del usuario (30-oct-2026): no entra en v1**,
  queda para versiones futuras; abrir desde el menú principal superior funciona.
- Subtítulos externos: **validados en la Thor (30-oct-2026)** con ficheros de prueba
  temporales en el NAS (ya borrados): aparecen en el menú de pistas con el nombre
  real, se pintan sobre el vídeo y «Desactivado» los apaga.
- Carátulas: **validadas en la Thor** con MP3 con portada incrustada y MP3 sin
  portada (icono de nota, sin hueco roto), además de FLAC previo por SMB.
- **M4 cerrado**: pósteres de vídeo y caché de metadatos implementados y validados
  (ver `docs/testing-m4.md`). Primer póster de un MKV grande por SMB tarda segundos
  (seek remoto) y luego queda cacheado; mejora futura: prefetch al entrar en carpeta.
- **Gestos del visor**: **validados en la Thor y por el usuario con dedos**
  (doble toque ±10 s a ambos lados, arrastre proporcional con vista previa,
  pinch/pan/doble toque en imagen).
- **Multi-resume y foco de audio: validados** (tocar la pantalla inferior no pausa;
  HOME pausa por diseño; al volver se restauran ambas ventanas y la ficha; el audio
  se pausa al pedir foco otra app, confirmado por el usuario).
- **FUERA DE v1 — audio en segundo plano con MediaSession**: decidido el
  30-oct-2026 no implementarlo en v1; se pospone a versiones posteriores.

## Resuelto en el cierre de M2 (30-oct-2026)

- **Menú de pistas desbordado** con muchas pistas (un MKV con 17 subtítulos + 2
  `.srt` externos): las últimas pistas se salían de la pantalla y no se podían
  alcanzar. Ahora `TrackColumn` usa `LazyColumn` con altura máxima y auto-scroll a
  la selección.
- **Nombres genéricos de los subtítulos externos**: libVLC los expone como
  «Track 16/17»; `SlaveTrackNames` los renombra con el nombre real del fichero.
- **Panel de audio cortado**: la superficie útil es 1920×970 y los controles fijos
  quedaban fuera. Compactado (carátula 180 dp) + scroll de seguridad.
- **Pantalla superior en negro al volver del segundo plano**: `onAppForegrounded`
  muestra los controles del vídeo pausado y se autoocultan al reanudar (`X`).
- **Doble toque de zoom en el visor de imagen**: no se reconocía porque
  `Modifier.transformable` consume el `down` antes que `detectTapGestures`; el
  detector de taps pasa a ser el modificador más interno. Verificado: 1× → 2,5× →
  1×. (Falló también con `adb input tap` doble, así que era un bug real, no de
  la prueba.)
- **Menú de pistas abierto por toque sin cierre táctil** (reportado por el usuario):
  ahora hay scrim modal y tocar fuera lo cierra; los controles no se pintan mientras
  el menú está abierto (tapaban sus últimas filas). Verificado en la Thor: abrir con
  el botón Audio → tocar fuera → menú cerrado.

## M5 cerrado (31-oct-2026)

- **Pantalla de contenido**: cambiar el contenido a la otra pantalla **no está
  soportado** en v1. Probado en la Thor: al relanzar la activity en el display
  secundario, la `Presentation` del explorador falla con
  `InvalidDisplayException: the specified display can not be found` y la app queda sin
  ventanas visibles. El ajuste muestra las pantallas detectadas y avisa; el contenido
  se queda en la pantalla donde se abre la app.
- **Visor de PDF**: en la Thor el único handler de `application/pdf` es una app de terceros (no
  es un visor). La app pregunta siempre y no lanza nada si no hay handler. **No
  validado** con un visor real por decisión del usuario (se presupone correcto); la
  descarga a caché y el selector sí están validados con ZIP.
- **Búsqueda local**: busca solo en la carpeta SAF configurada (o en la ubicación
  actual si es local); desde una carpeta SMB busca en la carpeta local configurada.
  Límites: profundidad 6, 5.000 entradas, 200 resultados.
- **Externos SMB**: la primera apertura descarga el archivo (progreso en la superior);
  el PDF de prueba de 150 MB tardó 17,7 s y quedó en caché (`cache/external`, poda
  40 ficheros / 256 MB).
- **Rendimiento**: 5.000 entradas cubiertas por tests JVM; en dispositivo se probó una
  carpeta SMB de 561 elementos sin ANR (no hay carpeta real de 5.000 accesible).

## M6 cerrado (31-oct-2026) — v1.0.0

- **UI de depuración eliminada**: barra de diagnóstico de la pantalla superior, pista
  «tecla: …» de la barra inferior y entradas «Vídeo/Imagen de prueba» y «Vídeo con app
  externa» de la raíz (los extras de `adb` siguen existiendo para pruebas internas).
- **Idiomas**: inglés por defecto; español en Ajustes → Idioma. Textos en
  `values/strings.xml` (EN) y `values-es/strings.xml` (ES).
- **Firma**: keystore propio en `.secrets/` (ignorado por git). Sin copia de seguridad
  del keystore no se podrán publicar futuras actualizaciones.
- **Limitaciones de v1**: solo lectura; sin mover el contenido de pantalla; PDF
  presupuesto (sin visor validado); búsqueda local (SAF) acotada; SMB1 sin validación
  positiva real; 5.000 entradas solo en tests JVM.

# Validación en la AYN Thor — M1 y M2

Checklist para la pasada en el dispositivo (M0 ya está validado). Se puede hacer en
dos tandas: lo automático con `scripts/smoke-device.sh` y lo manual con mando/táctil.

## Preparación

1. Thor encendida y **Depuración inalámbrica** activa. Puerto nuevo:
   ```fish
   echo 192.0.2.82:PUERTO > ~/Huginn/.tmp/adb-target
   ```
2. SMB configurado (si hace falta): `bash scripts/push-smb-config.sh`.
3. `source scripts/env.sh && ./gradlew assembleDebug` (o usar el APK ya compilado).

## Automático: `bash scripts/smoke-device.sh`

Instala el APK y guarda capturas localmente (no se versionan):

| Captura | Qué valida |
|---|---|
| `01-raiz-*` | raíz con acciones, ficha superior, migas |
| `02-smb-lista-*` | listado SMB + ficha con origen/orden |
| `03-smb-grid-*` | grid 2 columnas (miniatura solo si es SAF; SMB muestra icono) |
| `04-smb-galeria-*` | elegibilidad de galería (carpeta solo con subcarpetas) |
| `05-menu` | menú Start («⋯») con vista/orden/caché/ajustes/salir |
| `06-orden-fecha` | orden aplicado desde la hoja |
| `07-video-controles` | controles superpuestos del vídeo |
| `08-video-sin-controles` | autoocultado a los 3 s |
| `09-video-pistas` | menú de pistas Audio/Subtítulos |
| `10-imagen` | visor de imagen a pantalla completa |

> El script fuerza `am start … --es open_path/--es view/--ez test_video` para no
> depender del mando. Si algo falla, mirar `scripts/device.sh logcat -d -s
> HuginnSmb HuginnVm HuginnVlc HuginnMain HuginnContent`.

## Ya validado automáticamente (29-oct-2026)

- Catálogo completo de vistas, menú/hoja de orden, vídeo con controles y pistas,
  galería con imágenes reales SMB, ficha con preview, audio SMB y letra japonesa.
  Ver el histórico del README.

## Manual (mando y tacto)

### Explorador
- [ ] **Táctil**: tocar fila = seleccionar; segundo toque o doble toque = abrir;
      scroll con inercia; migas tocables; botones de app bar (atrás, subir,
      refrescar, ordenar, vista, menú) con zona ≥48 dp.
- [ ] **Mando**: ↑/↓ mueven selección; ←/→ en lista no hacen nada (salvo visor);
      A abre; B sube un nivel (y en raíz sale); Start abre el menú; Y sin efecto en
      el explorador (reservado a controles del visor); Select alterna foco.
- [ ] **Grid**: ↑/↓ saltan de fila, ←/→ se mueven por columnas sin salirse.
- [ ] **Vista Galería**: en carpeta con vídeo/documento → aviso y sigue en lista;
      en carpeta solo con imágenes → galería 4 columnas cuadradas sin nombres.
- [ ] **Orden**: nombre/fecha/tamaño/tipo, asc/desc; persiste al cerrar y reabrir.
- [ ] **Vista**: la vista elegida persiste al cambiar de carpeta y al reiniciar.
- [ ] **SAF**: elegir carpeta (Android 13 no deja raíz ni `Download`; elegir una
      subcarpeta, p. ej. `Documents/…` o `Pictures/…`), ver miniaturas en grid y
      abrir una imagen.
- [ ] **SMB**: navegar `Data/…` varios niveles, back y migas; refrescar.

### Visor / reproducción
- [ ] Vídeo: controles visibles al entrar, **se ocultan a los 3 s**, vuelven con
      cualquier tecla y con el toque en la pantalla superior.
- [ ] X play/pausa, L1/R1 ±10 s (mantener repite), L2/R2 pista anterior/siguiente.
- [ ] Y alterna controles; B cierra y vuelve a la ficha del explorador.
- [ ] **Pistas**: Start (o botones Audio/Subtítulos) abre el menú; ↑/↓ elige,
      ←/→ cambia de columna, ✓ y ● marcan activa; se aplica al instante; B cierra.
- [ ] **Subtítulos externos**: un vídeo con `peli.srt`/`peli.es.srt` al lado los
      muestra en el menú de subtítulos y se pintan sobre el vídeo.
- [ ] Imagen: ←/→ y L2/R2 navegan con wrap; contador `n/total`; B cierra.
- [ ] **Gestos del vídeo (estilo YouTube)**: doble toque en la **mitad izquierda**
      = −10 s y en la **derecha** = +10 s (aviso temporal con la posición destino);
      **arrastre horizontal** = desplaza la reproducción en proporción al recorrido
      y en la dirección del gesto (un ancho de pantalla ≈ 2 min, acotado a la
      duración), con vista previa durante el gesto y salto exacto al soltar; un
      **toque simple** sigue mostrando/ocultando los controles.
- [ ] **Gestos de la imagen**: **pinch** = zoom 1×–4× hacia el punto pellizcado;
      arrastre con el zoom aplicado = mover (con topes); **doble toque** alterna
      1×/2,5×; ←/→ siguen navegando la galería.
- [ ] Audio: controles fijos, barra de progreso, metadatos (título/artista/álbum) y
      **columna de letra** con `.lrc` (línea activa en ámbar, auto-scroll) o `.txt`
      (estática). Si no hay letra, no aparece la columna.
- [ ] Foco de audio: reproducir audio y abrir otra app con sonido → se pausa.
- [ ] Mantener pantalla encendida durante la reproducción.

### Verificado con el usuario (29-oct-2026)

- [x] Audio con libVLC (forzando `--aout=opensles`; `audiotrack` quedaba mudo).
- [x] L1/R1 (seek) no muestra los controles; el nombre del vídeo solo con controles.
- [x] Táctil sin retardo: un toque selecciona al instante.
- [x] SAF con carpeta `Documentos` del usuario.
- [ ] Revisar con el usuario el placeholder «Generando…» y la ficha de carpeta
      centrada (capturas ya verificadas por el agente).

### Pendiente / limitaciones conocidas en esta pasada
- Mini-player: **validado visualmente en la Thor** (título, tiempo y estado
  pausa/reproducción); queda confirmar con el dedo el toque = foco al reproductor.
- Carátulas: **validadas** con FLAC/MP3 con portada y con un MP3 **sin** portada.
- Miniaturas y metadatos de **SMB**: M4 (Coil no lee `smb://`).
- Multi-resume: **validado**. Pendiente solo la caducidad del foco de audio (pausa
  al pedirlo otra app), que necesita oído del usuario.
- **Fuera de v1**: audio en segundo plano (MediaSession) y apertura desde el dock
  de un launcher de terceros en la pantalla inferior.

### Fixtures preparados para cerrar M2 (30-oct-2026) — **borrados del NAS tras validar**

- **Vídeo con pistas**: `Data/Media/Movies/Example Movie (2020)/Example Movie (2020) Bluray-1080p.mkv`
  (primera película del listado; 2,5 GB). Sirve para el menú de pistas de
  audio/subtítulos incrustados.
- **Subtítulos externos** (creados por el agente durante la validación y
  **borrados al terminar**): `…/Example Movie (2020) Bluray-1080p.srt` (coincidencia
  exacta de nombre) y `…/Example Movie (2020) Bluray-1080p.es.srt` (variante de idioma).
  Para repetir la prueba, volver a subirlos (`smbclient put`) y **cerrar la
  reproducción antes de borrarlos** (si no, Samba da `NT_STATUS_SHARING_VIOLATION`
  porque libVLC mantiene el `.srt` abierto como slave).
- **MP3 con portada**: `Data/Media/Music/Mp3/Sample Album/`
  (carátula MJPEG incrustada, comprobada con `ffprobe`).
- **MP3 sin portada**: `Data/Media/Music/Mp3/Sample Album/Sample Track.mp3`
  (y `Bonanza.mp3`).

### Lanzar la película sin tocar el mando

```fish
# fish (shell del usuario)
set -gx HUGINN_ADB_TARGET 192.0.2.82:PUERTO   # o actualizar .tmp/adb-target
bash scripts/device.sh shell am start -n dev.huginn.debug/dev.huginn.MainActivity \
  --es open_path "smb://192.0.2.10/Data/Media/Movies/Example Movie%20(2020)" \
  --ez play_first true
```

Los extras de desarrollo (`open_path`, `play_first`, `track_menu`, `test_video`…)
están descritos en el README.

## Validado por el agente en la Thor (30-oct-2026, pasada automática)

Capturas en `.tmp/m2/` (no versionadas). Comandos con `adb` (extras dev + `input`):

- [x] Doble toque derecha/izquierda = **+10 s/−10 s** con aviso y posición destino
      (0:35 → 0:45 → 0:44 sobre *Example Movie*).
- [x] Arrastre horizontal proporcional con vista previa: ~860 px ≈ **+0:54** y
      **−0:55** (un ancho de pantalla ≈ 2 min, acotado por el medio).
- [x] Toque simple sigue mostrando/ocultando los controles.
- [x] **Menú de pistas** con 2 pistas de audio (English/Japanese) y **17 subtítulos
      incrustados + 2 externos**: scroll y selección visible; RIGHT/DOWN/A aplican
      al instante; «Desactivado» apaga el subtítulo. Abierto por toque, **tocar
      fuera lo cierra** (scrim modal); con mando, `B`/`Start`/`A`.
- [x] **Subtítulos externos `.srt` por SMB**: se pintan sobre el vídeo («Subtítulo
      de prueba Huginn…», «Variante de idioma .es.srt…»).
- [x] **Carátulas MP3**: con portada (carátula MJPEG + título/artista/álbum) y sin
      portada (icono de nota).
- [x] **Multi-resume**: `input -d 4 tap` en la inferior no pausa (0:10 → 0:13);
      HOME pausa; al volver, `Presentation` inferior restaurada y ficha con
      controles del vídeo pausado.
- [x] **Keep-screen-on** en las dos ventanas (`dumpsys window`: `fl=KEEP_SCREEN_ON`).
- [x] **Zoom/pan de imagen**: matemática cubierta con tests JVM (`ImageTransformTest`:
      acotado 1×–4×, topes de arrastre, zoom alrededor del punto pellizcado).
      **Doble toque validado en la Thor** (1× → 2,5× → 1×) tras corregir el orden
      de modificadores (`transformable` se comía el `down`). Falta solo el pinch
      con dedos en la pasada manual.

### Pasada manual del usuario (30-oct-2026)

- [x] Dedos: doble toque, arrastre de seek, pinch zoom y doble toque de zoom.
- [x] Mando real por el menú de pistas (`Start`, cruceta, `A`/`B`).
- [x] Mini-player.
- [x] **Foco de audio**: se pausa al arrancar sonido en otra app y sigue sonando al
      volver.
- [x] Único fallo: el menú de pistas abierto por toque no se cerraba con el dedo →
      corregido (scrim modal, tocar fuera cierra) y verificado en la Thor.

**M2 cerrado el 30-oct-2026.**

## Guion de la pasada manual (≈10 min, con el usuario)

Preparación del agente (Thor despierta y conectada):

```bash
bash scripts/device.sh shell settings put system screen_off_timeout 1800000
# keep-alive: SHIFT_LEFT (59) cada 10 s mientras dure la prueba
```

1. **Toque del visor de vídeo** (*Example Movie* por SMB):
   - un toque muestra/oculta los controles;
   - doble toque derecha suma 10 s y deja el aviso `+0:10` un instante;
   - doble toque izquierda resta 10 s;
   - arrastre horizontal: el tiempo avanza/retrocede **en proporción al recorrido**
     (≈2 min por ancho de pantalla) con vista previa durante el gesto.
2. **Visor de imagen**: abrir una imagen; pinch para zoom, arrastrar con el zoom
   puesto, doble toque para 1×/2,5×; ←/→ siguen cambiando de imagen.
3. **Mando real**: `Start` abre el menú de pistas; ↑/↓ recorren la lista (con
   scroll si hay muchas pistas), ←/→ cambian de columna, `A` aplica y cierra;
   `B` cierra. Comprobar que la selección siempre se ve.
4. **Mini-player**: con audio o vídeo sonando, tocar el mini-player lleva el foco
   al reproductor; play/pausa y tiempo correctos.
5. **Foco de audio** (necesita oído): reproducir un FLAC/MP3 en Huginn y abrir
   YouTube o VLC con sonido → Huginn debe **pausarse**; al volver a Huginn y
   reanudar, el audio debe **seguir sonando** (no quedarse mudo: el `aout` de la
   Thor es delicado; evitar `stop()` en caliente).
6. **Sin regresiones**: navegar carpetas mientras suena (no corta), pantalla
   inferior apagada (⏻) y vuelta a encender con un toque.

Criterio para cerrar M2: los 6 pasos OK → marcar el objetivo como completado y
anotar el resultado en esta nota y en el README del proyecto.

# Validación de M4 (previews y miniaturas) en la AYN Thor

Fecha: 30-oct-2026. Complementa `docs/testing-m1-m2.md` y `docs/testing-m3.md`.

## Automático

- `./gradlew :app:testDebugUnitTest :app:lintDebug` → **105 tests verdes**, lint sin
  errores. Cubren el codec y la caché persistente de metadatos (LRU, corrupción,
  versión), la serialización Base64 v2 y los formatos de duración/resolución.

## Pasada en la Thor (evidencias en `.tmp/m4/`)

1. **Póster de vídeo en grid**: carpeta de película por SMB (*Example Movie*, MKV 2,8 GB);
   el primer fotograma se extrae con `MediaMetadataRetriever` sobre acceso aleatorio y
   se cachea en disco (`cache/thumbs`, JPEG). Primer cálculo: **~13,5 s** por el seek
   en un MKV grande por SMB; después, instantáneo.
2. **Fotograma representativo**: a 1 s salía negro (intro); ahora se elige al **10 %
   de la duración** (y 35 % si el brillo medio es casi negro). Verificado: escena real.
3. **Chip de duración** en el grid (`2:24:29`) tras leer los metadatos (la sonda de
   facts tarda **3-86 ms**: solo cabecera).
4. **Ficha detallada**:
   - vídeo: póster + `Duración 2:24:29` + `Resolución 1920×1080` + fecha/ruta;
   - audio: `Duración 3:47` + `Metadatos: título · artista · álbum`;
   - imagen: preview + `Dimensiones 1920×1080`;
   - audio: **carátula** en la ficha (extraída por acceso aleatorio) además de
     duración y etiquetas; tras matear el proceso y reabrir, la carátula vuelve de la
     caché de disco sin releer los metadatos.
5. **Persistencia**: tras sondear y salir (flush en `onStop`), se **mateó el proceso** y
   al reabrir la carpeta el póster salió de disco y el chip de duración apareció sin
   volver a sondear (caché cargada: 1 entrada; sin log de sonda). 214 miniaturas en
   `cache/thumbs` y `files/media-facts.properties` en formato v2.
6. **Selección rápida de 50**: 50 `DOWN` inyectados en **809 ms**; la selección avanzó
   ~50 posiciones sin ANR (`dumpsys gfxinfo`: mediana 16 ms, p90 42 ms, jank ~24 % en
   esa ráfaga con miniaturas cargando).
7. **Vista Galería en carpeta mixta** (`Sample Images`: 89 subcarpetas + 112
   imágenes): se ofrece la galería, **solo imágenes** (111 elementos), 4 columnas
   cuadradas sin nombres y subcarpetas ocultas.

## Correcciones/decisiones de esta pasada

- **Caché de metadatos v2**: el `Properties` de Android *perdía separadores* `0x1F` al
  releer el fichero (en JVM no); ahora cada campo va en Base64 y se unen con `|`.
  Los ficheros v1 se descartan y se regeneran.
- **Un único póster por vídeo** (900 px, clave `vid3`) compartido por grid y ficha:
  antes grid (512) y ficha (900) sondeaban el fichero dos veces.
- **Init ordering (otra vez)**: el hilo que carga la caché de metadatos se lanzaba en un
  `init` anterior a la declaración de `mediaFacts` → NPE al arrancar; movido detrás.
- **Aviso de la caché al arrancar**: tras cargar el fichero se notifica a la UI para
  que chips/ficha se repinten con los datos persistidos.

## Limitaciones

- El **primer** póster de un MKV grande por SMB tarda segundos (seek remoto). Se cachea
  y hay cola de 2; no bloquea la UI. Mejora futura: prefetch al entrar en la carpeta.
- Póster = fotograma del propio vídeo, no póster descargado (TMDB/Internet no está en
  alcance de v1).

# Validación de M5 (externos, UX y robustez) en la AYN Thor

Fecha: 30/31-oct-2026. Estado: **cerrado** (validado en la AYN Thor).

## Externos no multimedia (SMB)

- **Descarga a caché con progreso**: `ExternalCache` descarga por SMB a
  `cache/external` y comparte por `FileProvider`; SAF usa su `content://` directo.
  Progreso en la pantalla superior (`TopPanelState.ExternalDownload`) y cancelación
  con `B` (o botón Cancelar). Verificado con el ZIP de 1,4 MB (198 ms) y el PDF de
  150 MB (17,7 s) — el PDF se descargó entero y quedó en caché.
- **Nunca se abre en la inferior**: la app externa se lanza con trampolín arriba
  (`TrampolineActivity` + `launchDisplayId`); verificado que el ZIP abre en
  `pl.solidexplorer2` sobre el display 0.
- **Selector «Abrir con» propio** (hoja inferior navegable con mando y toque):
  - lista solo apps que declaran **el tipo real** (sin fallback a `*/*`, que metía
    instaladores/Wallet — detectado con pruebas reales);
  - requiere `<queries>` en el manifiesto para ver paquetes en Android 11+ (sin él
    Solid Explorer no aparecía);
  - opción **«Recordar para este tipo»**: verificado que tras marcarla y elegir
    Solid Explorer, la siguiente apertura del ZIP fue directa sin selector.
- **Sin lanzamiento a ciegas**: se eliminó la resolución automática por app por
  defecto. Si no hay apps para el tipo, se muestra «No hay ninguna app instalada que
  abra «application/pdf»» y no se lanza nada.

## Caso real y decisión de seguridad

Al probar un PDF, el único handler registrado en la Thor era **una app de terceros** (una app
de Pokémon que declara PDF/ZIP) y se lanzó por error. El usuario avisó de que no es un
visor de PDF y podía tocar sus partidas. Tras ello:

- el selector **siempre pregunta** (no hay auto-resolución);
- se listan solo handlers del tipo exacto;
- **un PDF no se puede validar en esta Thor** hasta instalar un visor real; la app se
  niega a abrirlo (mensaje claro) en vez de lanzar una app inadecuada.

## Dev

Extra nuevo para pruebas deterministas: `--es open_name "<nombre>"` selecciona y abre
ese elemento por nombre (evita depender de índices de teclas).

## Bloque UX (implementado; validación en dispositivo pendiente)

- **Ajustes** general: limpiar caché (miniaturas/externos/metadatos), SMB, **pantalla
  de contenido** (superior/inferior con relanzado en el display resuelto), mapeo de
  botones, acerca de.
- **Recientes y favoritos** persistentes (`files/places.properties`, `PlacesStore`):
  recientes máx. 30 sin duplicados, favoritos máx. 50 con alternado desde el menú.
- **Búsqueda local** SAF con debounce y límites (profundidad 6, 5.000 entradas, 200
  resultados) y campo en la pantalla superior.
- **Mapeo de botones** con modo aprendizaje (`KeyMapCodec`, overrides en `AppSettings`,
  `InputRouter` los aplica al instante).
- **Estados vacíos** contextuales.
- Automático: **129 tests verdes**, lint sin errores y `assembleRelease` compila.

Pendiente en M5: validar este bloque en la Thor (la unidad se durmió y cambió el
puerto de depuración), validar PDF con un visor adecuado y cerrar con el rendimiento
en una carpeta grande real.

## Resultados de la pasada final en la Thor (31-oct-2026)

- **Raíz**: muestra «Recientes» y «Favoritos» con contador y «Ajustes» general.
- **Recientes**: abrir un audio → raíz → «Recientes (1)» → lista con el archivo →
  A lo abre (reproductor con carátula y metadatos).
- **Favoritos**: menú → «Añadir a favoritos» sobre el reciente → raíz → «Favoritos (1)»
  → lista y apertura correctas; el fichero `files/places.properties` guarda la ruta
  **sin credenciales** (verificado).
- **Ajustes**: lista con 5 entradas; «Limpiar caché» dejó `cache/thumbs` y
  `cache/external` en 0; «Servidores SMB» abre la pantalla M3; «Acerca de» avisa.
- **Mapeo de botones**: 15 filas (14 acciones + restaurar); aprender «Abajo» asignando
  `KEYCODE_BUTTON_Y` (`input keyevent 100`) → `shared_prefs` queda `key_map=DOWN=100`
  y la lista lo refleja; «Restaurar mapeo por defecto» borra el override.
- **Búsqueda local**: con la carpeta SAF configurada (`tree/FFFF-B6EF:Music`), menú →
  «Buscar aquí…» y término `thumb` → 1 resultado (`.thumbnails`) en la inferior; la
  raíz local tenía solo esa carpeta, así que no hubo búsqueda de medios reales (el
  motor está cubierto por tests: profundidad, límites y coincidencias).
- **Rendimiento**: carpeta SMB de **561 elementos** carga y pinta el grid sin ANR
  (los 5.000 están cubiertos por tests JVM; no hay carpeta real de 5.000 accesible).

## Pantalla de contenido: NO viable en la Thor (decisión)

Se probó a mover el contenido a la pantalla inferior relanzando la activity con
`launchDisplayId`. Resultado real: la app queda **sin ventanas visibles** y la
`Presentation` del explorador falla con
`InvalidDisplayException: the specified display can not be found` (al mostrarla desde
la activity ya colocada en el display secundario). Por eso el ajuste pasa a ser
**informativo** (pantallas detectadas y aviso) y el contenido se queda en la pantalla
donde se abre la app. La lógica pura de decisión (`DisplayRouter.pick` /
`needsRelocation`) queda cubierta por tests para una v2 que lo aborde de otra forma.

## PDF: no validado por decisión del usuario

En la Thor solo una app de terceros declara `application/pdf` (no es un visor, y se descartó por
seguridad). El usuario pidió no instalar nada: se **presupone** que con un visor real
el selector funcionaría (la descarga a caché y el selector están validados con ZIP:
Solid Explorer y «usar siempre»).

package dev.huginn.ui.top

import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.huginn.core.MediaFacts
import dev.huginn.data.FsEntry
import dev.huginn.data.MediaKind
import dev.huginn.ui.thumb.FsThumbnail
import dev.huginn.media.Lyrics
import dev.huginn.media.LyricsParser
import dev.huginn.R
import dev.huginn.ui.theme.HuginnColors
import dev.huginn.ui.theme.HuginnTypography
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Contenido de la pantalla superior (spec §4). M0 cubre: ficha del directorio,
 * ficha de selección con miniatura de imagen (Coil), visor de imagen y reproducción
 * de vídeo/audio sobre libVLC. Los controles superpuestos y el menú de pistas llegan
 * en M2.
 */
/** Acciones táctiles del panel superior (paridad con el mando, spec §1.2.1). */
data class TopPanelActions(
    val onPlayPause: () -> Unit = {},
    val onSeekBack: () -> Unit = {},
    val onSeekForward: () -> Unit = {},
    /** Salto exacto, usado por el arrastre horizontal (seek proporcional). */
    val onSeekTo: (Long) -> Unit = {},
    val onPrev: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onToggleControls: () -> Unit = {},
    val onOpenTrackMenu: () -> Unit = {},
    val onCloseTrackMenu: () -> Unit = {},
    /** Formulario de servidor SMB (M3): cambios y acciones. */
    val onServerFormChange: (TopPanelState.ServerForm) -> Unit = {},
    val onServerFormSave: () -> Unit = {},
    val onServerFormDelete: () -> Unit = {},
    val onServerFormCancel: () -> Unit = {},
    /** M5: cancelar la preparación/descarga de un externo. */
    val onCancelExternal: () -> Unit = {},
    /** M5: texto de la búsqueda local (teclado en la pantalla superior). */
    val onSearchTermChange: (String) -> Unit = {},
    /** M5: salir del aprendizaje de un botón. */
    val onCancelLearnKey: () -> Unit = {},
    val onSelectTrack: (Int, Int) -> Unit = { _, _ -> },
)

@Composable
fun TopPanelContent(
    state: TopPanelState,
    attachVideoView: (SurfaceView?) -> Unit,
    modifier: Modifier = Modifier,
    actions: TopPanelActions = TopPanelActions(),
    loadImage: suspend (FsEntry, Int) -> ImageBitmap? = { _, _ -> null },
    /** M4: revisión de metadatos y consulta de facts para la ficha detallada. */
    factsVersion: Int = 0,
    factsOf: (FsEntry) -> MediaFacts? = { null },
) {
    Box(modifier = modifier.fillMaxSize().background(HuginnColors.Bg)) {
        when (state) {
            is TopPanelState.DirectoryInfo -> DirectoryInfoContent(state)
            is TopPanelState.EntryInfo -> EntryInfoContent(state, loadImage, factsVersion, factsOf)
            is TopPanelState.ImageViewer -> ImageViewerContent(state, loadImage)
            is TopPanelState.Playing -> PlayingContent(state, attachVideoView, actions)
            is TopPanelState.ServerForm -> ServerFormContent(state, actions)
            is TopPanelState.ExternalDownload -> ExternalDownloadContent(state, actions)
            is TopPanelState.SearchForm -> SearchFormContent(state, actions)
            is TopPanelState.LearnKey -> LearnKeyContent(state, actions)
            is TopPanelState.Error -> ErrorContent(state)
        }
    }
}

@Composable
private fun DirectoryInfoContent(state: TopPanelState.DirectoryInfo) {
    Row(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(18.dp)).background(HuginnColors.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text("📁", fontSize = 64.sp)
        }
        Spacer(Modifier.width(40.dp))
        Column {
            Text(
                state.title,
                style = HuginnTypography.display,
                color = HuginnColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.path.isNotBlank()) {
                Text(
                    state.path,
                    style = HuginnTypography.secondary,
                    color = HuginnColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val chips = state.chips
                if (chips != null) {
                    chips.forEach { Chip(it) }
                } else {
                    Chip(stringResource(R.string.chip_dirs, state.directories))
                    Chip(stringResource(R.string.chip_files, state.files))
                }
            }
            Spacer(Modifier.height(12.dp))
            state.sourceLabel.takeIf { it.isNotBlank() }?.let { RowDetail(stringResource(R.string.detail_source), it) }
            state.sortLabel.takeIf { it.isNotBlank() }?.let { RowDetail(stringResource(R.string.detail_sort), it) }
            if (state.readOnly) RowDetail(
                    stringResource(R.string.detail_permissions),
                    stringResource(R.string.read_only),
                )
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.hint ?: stringResource(R.string.detail_hint),
                style = HuginnTypography.secondary,
                color = HuginnColors.Dim,
            )
        }
    }
}

@Composable
private fun EntryInfoContent(
    state: TopPanelState.EntryInfo,
    loadImage: suspend (FsEntry, Int) -> ImageBitmap?,
    factsVersion: Int,
    factsOf: (FsEntry) -> MediaFacts?,
) {
    val facts = remember(factsVersion, state.entry.path) { factsOf(state.entry) }
    Row(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryPreview(state, loadImage, factsVersion, factsOf)
        Spacer(Modifier.width(32.dp))
        Column {
            Text(
                state.name,
                style = HuginnTypography.display,
                color = HuginnColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(kindLabel(state.kind), state.sizeText).joinToString(" · ")
            Text(meta, style = HuginnTypography.body, color = HuginnColors.Muted)
            Spacer(Modifier.height(12.dp))
            // Ficha detallada (M4): duración/resolución/etiquetas cuando se conocen.
            facts?.durationText?.let { RowDetail(stringResource(R.string.detail_duration), it) }
            facts?.resolutionText?.let {
                RowDetail(
                if (state.kind == MediaKind.IMAGE) stringResource(R.string.detail_dimensions)
                else stringResource(R.string.detail_resolution),
                it,
            )
            }
            facts?.tagsText?.let { RowDetail(stringResource(R.string.detail_metadata), it) }
            state.dateText?.let { RowDetail(stringResource(R.string.detail_modified), it) }
            RowDetail(stringResource(R.string.detail_path), state.path)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.hint_file), style = HuginnTypography.secondary, color = HuginnColors.Dim)
        }
    }
}

/**
 * Hueco visual de la ficha. Coherente con la ficha de directorio:
 * - imágenes: caja de preview grande (mockup 02) con «Generando…» mientras carga;
 * - carpetas y otros tipos: **tile compacto** con el icono centrado, sin el hueco
 *   enorme de una preview que no existe.
 */
@Composable
private fun EntryPreview(
    state: TopPanelState.EntryInfo,
    loadImage: suspend (FsEntry, Int) -> ImageBitmap?,
    factsVersion: Int,
    factsOf: (FsEntry) -> MediaFacts?,
) {
    val poster = state.kind == MediaKind.IMAGE ||
        state.kind == MediaKind.VIDEO ||
        state.kind == MediaKind.AUDIO
    if (poster) {
        Box(
            modifier = Modifier
                .size(width = 420.dp, height = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(HuginnColors.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (state.previewLoading) {
                Text(stringResource(R.string.generating), style = HuginnTypography.body, color = HuginnColors.Dim)
            } else {
                FsThumbnail(
                    entry = state.entry,
                    targetPx = 900,
                    loader = loadImage,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                ) {
                    Text(glyphFor(state.kind), fontSize = 88.sp)
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(HuginnColors.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyphFor(state.kind), fontSize = 88.sp)
        }
    }
}

@Composable
private fun ImageViewerContent(
    state: TopPanelState.ImageViewer,
    loadImage: suspend (FsEntry, Int) -> ImageBitmap?,
) {
    // Zoom/pan por imagen (spec: pinch = zoom, doble toque alterna 1×/2,5×).
    // Al cambiar de imagen se vuelve a 1×. La matemática vive en ImageTransform
    // (sin tipos de Compose, testeada en JVM).
    var scale by remember(state.entry) { mutableFloatStateOf(1f) }
    var offsetX by remember(state.entry) { mutableFloatStateOf(0f) }
    var offsetY by remember(state.entry) { mutableFloatStateOf(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val zoom = ImageTransform.nextScale(scale, zoomChange)
        val (x, y) = ImageTransform.nextOffset(
            offsetX = offsetX,
            offsetY = offsetY,
            centroidX = centroid.x,
            centroidY = centroid.y,
            viewWidth = viewSize.width.toFloat(),
            viewHeight = viewSize.height.toFloat(),
            scaleBefore = scale,
            scaleAfter = zoom,
            panX = panChange.x,
            panY = panChange.y,
        )
        scale = zoom
        offsetX = x
        offsetY = y
    }

    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                // Orden importante: `transformable` consume el down del primer
                // puntero, así que el detector de taps tiene que ser el modificador
                // MÁS INTERNO (se procesa antes en el pase Main). Al revés, el
                // doble toque nunca llegaba (zoom roto en la primera build).
                .transformable(state = transformState)
                .pointerInput(state.entry) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = ImageTransform.doubleTapScale(scale)
                            offsetX = 0f
                            offsetY = 0f
                        },
                    )
                },
        ) {
            FsThumbnail(
                entry = state.entry,
                targetPx = 1920,
                loader = loadImage,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentScale = ContentScale.Fit,
            ) {
                Text(stringResource(R.string.loading), style = HuginnTypography.body, color = HuginnColors.Dim)
            }
        }
        Text(
            text = stringResource(R.string.image_counter, state.index, state.total, state.name),
            style = HuginnTypography.secondary,
            color = HuginnColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp)
                .background(HuginnColors.Surface.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            stringResource(R.string.image_hint),
            style = HuginnTypography.micro,
            color = HuginnColors.Muted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }
}

@Composable
private fun PlayingContent(
    state: TopPanelState.Playing,
    attachVideoView: (SurfaceView?) -> Unit,
    actions: TopPanelActions,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.isVideo) {
            // La superficie de libVLC vive aquí; al desmontarse se desengancha.
            DisposableEffect(Unit) {
                onDispose { attachVideoView(null) }
            }
            AndroidView(
                factory = { context ->
                    SurfaceView(context).also { attachVideoView(it) }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Gestos táctiles sobre el vídeo: doble toque ±10 s y arrastre
            // horizontal proporcional (estilo YouTube).
            VideoGestureLayer(
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxSize(),
            )
            // El nombre/tiempo del vídeo solo se muestra junto a los controles.
            if (state.controlsVisible) {
                Text(
                    text = "${state.name}  ·  ${formatTime(state.positionMs)} / ${formatTime(state.lengthMs)}",
                    style = HuginnTypography.secondary,
                    color = HuginnColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(20.dp)
                        .background(HuginnColors.Surface.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // Fondo modal del menú de pistas: un toque fuera lo cierra (además de
            // B/Start con el mando). Sin esto, con el dedo no había forma de cerrarlo.
            if (state.trackMenu != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { actions.onCloseTrackMenu() })
                        },
                )
            }
            // Menú de pistas (overlay): Audio | Subtítulos, aplica al instante.
            state.trackMenu?.let { menu ->
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(HuginnColors.Panel.copy(alpha = 0.95f))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        TrackColumn(
                            title = stringResource(R.string.track_audio),
                            items = menu.audio,
                            column = 0,
                            selection = menu.audioIndex,
                            activeId = menu.audio.getOrNull(menu.audioIndex)?.id,
                            onSelect = actions.onSelectTrack,
                        )
                        TrackColumn(
                            title = stringResource(R.string.track_subtitles),
                            items = menu.subtitles,
                            column = 1,
                            selection = menu.subtitleIndex,
                            activeId = menu.subtitles.getOrNull(menu.subtitleIndex)?.id,
                            onSelect = actions.onSelectTrack,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.track_close_hint),
                        style = HuginnTypography.micro,
                        color = HuginnColors.Dim,
                    )
                }
            }

            // Controles superpuestos: visibles al entrar y al interactuar; se ocultan a
            // los 3 s. Con el menú de pistas abierto no se pintan (modal): si no, su
            // panel tapaba las últimas filas del menú.
            if (state.controlsVisible && state.trackMenu == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(HuginnColors.Surface.copy(alpha = 0.85f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    ProgressBar(state.positionMs, state.lengthMs)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlButton("⏮", onClick = actions.onPrev)
                        ControlButton("−10", onClick = actions.onSeekBack)
                        ControlButton(if (state.playing) "⏸" else "▶", onClick = actions.onPlayPause, big = true)
                        ControlButton("+10", onClick = actions.onSeekForward)
                        ControlButton("⏭", onClick = actions.onNext)
                        ControlButton("✕", onClick = actions.onClose)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextChipButton(stringResource(R.string.track_audio_short), onClick = actions.onOpenTrackMenu)
                        Spacer(Modifier.width(10.dp))
                        TextChipButton(stringResource(R.string.track_subtitles_short), onClick = actions.onOpenTrackMenu)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.video_hint),
                        style = HuginnTypography.micro,
                        color = HuginnColors.Muted,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        } else {
            val lyrics = state.lyrics
            // La superficie útil de la Thor es 1920×970, no 1080: el panel de audio
            // se dimensiona para caber entero (antes los controles quedaban cortados
            // por abajo). El scroll es solo una red de seguridad en pantallas menores.
            Row(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val artwork = state.artwork
                    if (artwork != null) {
                        Image(
                            bitmap = artwork,
                            contentDescription = state.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(180.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                    } else {
                        Text("🎵", fontSize = 80.sp)
                    }
                    Text(
                        state.metadata?.title ?: state.name,
                        style = HuginnTypography.display,
                        color = HuginnColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 30.dp),
                    )
                    val meta = state.metadata
                    if (meta != null && (!meta.artist.isNullOrBlank() || !meta.album.isNullOrBlank())) {
                        Text(
                            listOfNotNull(meta.artist, meta.album).joinToString(" · "),
                            style = HuginnTypography.body,
                            color = HuginnColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // Audio: controles fijos (no autoocultan, spec §4.5).
                    ProgressBar(state.positionMs, state.lengthMs, modifier = Modifier.width(360.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.status_playing_time,
                            formatTime(state.positionMs),
                            formatTime(state.lengthMs),
                            stringResource(if (state.playing) R.string.playing else R.string.paused),
                        ),
                        style = HuginnTypography.body,
                        color = HuginnColors.Muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlButton("⏮", onClick = actions.onPrev)
                        ControlButton("−10", onClick = actions.onSeekBack)
                        ControlButton(if (state.playing) "⏸" else "▶", onClick = actions.onPlayPause, big = true)
                        ControlButton("+10", onClick = actions.onSeekForward)
                        ControlButton("⏭", onClick = actions.onNext)
                        ControlButton("✕", onClick = actions.onClose)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.video_hint_compact), style = HuginnTypography.micro, color = HuginnColors.Dim)
                }
                // Columna de letra solo si existe (si no, no se pinta hueco: spec §4.5)
                if (lyrics != null && !lyrics.isEmpty) {
                    Spacer(Modifier.width(28.dp))
                    LyricsColumn(
                        lyrics = lyrics,
                        positionMs = state.positionMs,
                        modifier = Modifier.width(430.dp).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Aviso efímero de salto (doble toque) o de arrastre en curso. */
private data class SeekHint(
    val deltaMs: Long,
    val targetMs: Long,
    val scrubbing: Boolean,
)

/**
 * Capa de gestos del vídeo, estilo YouTube (petición del usuario): doble toque en la
 * mitad izquierda = −10 s y en la derecha = +10 s; arrastre horizontal = seek
 * proporcional al recorrido, con vista previa y aplicado al soltar. Un toque simple
 * sigue mostrando/ocultando los controles.
 */
@Composable
private fun VideoGestureLayer(
    state: TopPanelState.Playing,
    actions: TopPanelActions,
    modifier: Modifier = Modifier,
) {
    val latest = rememberUpdatedState(state)
    var hint by remember { mutableStateOf<SeekHint?>(null) }

    // Cambiar de medio descarta cualquier aviso pendiente.
    LaunchedEffect(state.name) { hint = null }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(700)
            hint = null
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { actions.onToggleControls() },
                    onDoubleTap = { position ->
                        val delta = ViewerGestures.doubleTapDelta(position.x, size.width.toFloat())
                        if (delta >= 0) actions.onSeekForward() else actions.onSeekBack()
                        val playing = latest.value
                        hint = SeekHint(
                            deltaMs = delta,
                            targetMs = (playing.positionMs + delta).coerceIn(0L, playing.lengthMs),
                            scrubbing = false,
                        )
                    },
                )
            }
            .pointerInput(Unit) {
                var startMs = 0L
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        startMs = latest.value.positionMs
                        totalDx = 0f
                        hint = null
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDx += dragAmount
                        val playing = latest.value
                        if (playing.lengthMs > 0) {
                            val target = ViewerGestures.targetPositionMs(
                                startMs,
                                totalDx,
                                size.width.toFloat(),
                                playing.lengthMs,
                            )
                            hint = SeekHint(
                                deltaMs = target - startMs,
                                targetMs = target,
                                scrubbing = true,
                            )
                        }
                    },
                    onDragEnd = {
                        val playing = latest.value
                        if (playing.lengthMs > 0 && totalDx != 0f) {
                            val target = ViewerGestures.targetPositionMs(
                                startMs,
                                totalDx,
                                size.width.toFloat(),
                                playing.lengthMs,
                            )
                            if (target != startMs) actions.onSeekTo(target)
                        }
                        totalDx = 0f
                    },
                    onDragCancel = {
                        totalDx = 0f
                        hint = null
                    },
                )
            },
    ) {
        hint?.let { SeekHintOverlay(it, latest.value.lengthMs) }
    }
}

@Composable
private fun BoxScope.SeekHintOverlay(hint: SeekHint, lengthMs: Long) {
    val sign = if (hint.deltaMs >= 0) "+" else "−"
    val alignment = when {
        hint.scrubbing -> Alignment.Center
        hint.deltaMs >= 0 -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    Column(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HuginnColors.Surface.copy(alpha = 0.82f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$sign${formatTime(abs(hint.deltaMs))}",
            style = HuginnTypography.display,
            color = HuginnColors.Accent,
        )
        Text(
            "${formatTime(hint.targetMs)} / ${formatTime(lengthMs)}",
            style = HuginnTypography.body,
            color = HuginnColors.Muted,
        )
    }
}

@Composable
private fun ProgressBar(positionMs: Long, lengthMs: Long, modifier: Modifier = Modifier) {
    val fraction = if (lengthMs > 0) (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(HuginnColors.Border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(HuginnColors.Accent),
        )
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit, big: Boolean = false) {
    Box(
        modifier = Modifier
            .size(if (big) 52.dp else 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HuginnColors.Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = HuginnColors.Text, fontSize = if (big) 20.sp else 14.sp)
    }
}

@Composable
private fun TrackColumn(
    title: String,
    items: List<dev.huginn.media.MediaTrack>,
    column: Int,
    selection: Int,
    activeId: Int?,
    onSelect: (Int, Int) -> Unit,
) {
    // Un MKV puede traer muchas pistas (p. ej. 17 subtítulos + .srt externos): la
    // lista hace scroll y se auto-encuadra para que la selección del mando se vea.
    val listState = rememberLazyListState()
    LaunchedEffect(selection) {
        if (selection >= 0) listState.animateScrollToItem((selection - 2).coerceAtLeast(0))
    }
    Column(modifier = Modifier.width(220.dp)) {
        Text(title, style = HuginnTypography.micro, color = HuginnColors.Dim)
        Spacer(Modifier.height(6.dp))
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 300.dp)) {
            itemsIndexed(items) { index, track ->
                val selected = index == selection
                val active = track.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) HuginnColors.SelBg else Color.Transparent)
                        .clickable { onSelect(column, index) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = track.name,
                        style = HuginnTypography.secondary,
                        color = if (selected) HuginnColors.Accent else HuginnColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) Text("●", color = HuginnColors.Ok, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TextChipButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(HuginnColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = HuginnTypography.secondary, color = HuginnColors.Text)
    }
}

@Composable
private fun LyricsColumn(lyrics: Lyrics, positionMs: Long, modifier: Modifier = Modifier) {
    val active = LyricsParser.activeLineIndex(lyrics.lines, positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem((active - 3).coerceAtLeast(0))
    }
    Column(modifier = modifier) {
        Text(stringResource(R.string.lyrics), style = HuginnTypography.secondary, color = HuginnColors.Dim)
        Text(
            if (lyrics.synced) stringResource(R.string.lyrics_synced) else stringResource(R.string.lyrics_static),
            style = HuginnTypography.micro,
            color = HuginnColors.Dim,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isActive = index == active
                Text(
                    text = line.text.ifBlank { " " },
                    style = HuginnTypography.body.copy(
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (isActive) HuginnColors.Accent else HuginnColors.Muted,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Formulario de alta/edición de servidor SMB (M3). Vive en la ventana superior
 * porque la `Presentation` inferior no es focusable y no puede recibir el IME: aquí
 * el teclado del sistema funciona (táctil). Con mando: `A` guarda y `B` cancela.
 */
@Composable
private fun ServerFormContent(
    state: TopPanelState.ServerForm,
    actions: TopPanelActions,
) {
    val isNew = state.serverId == null
    var confirmDelete by remember { mutableStateOf(false) }

    // Solo se muestra en la pantalla superior (1920x970): dos columnas para que quepa
    // sin scroll incluso con el teclado abierto.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 18.dp),
    ) {
        Text(
            if (isNew) stringResource(R.string.server_new) else stringResource(R.string.server_edit),
            style = HuginnTypography.display,
            color = HuginnColors.Text,
        )
        Text(
            stringResource(R.string.server_password_note),
            style = HuginnTypography.secondary,
            color = HuginnColors.Dim,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                FormField(
                    label = stringResource(R.string.form_name),
                    value = state.label,
                    hint = stringResource(R.string.form_name_hint),
                    onValue = { actions.onServerFormChange(state.copy(label = it, error = null)) },
                )
                Spacer(Modifier.height(8.dp))
                FormField(
                    label = stringResource(R.string.form_host),
                    value = state.host,
                    hint = "192.0.2.10",
                    onValue = { actions.onServerFormChange(state.copy(host = it, error = null)) },
                )
                Spacer(Modifier.height(8.dp))
                FormField(
                    label = stringResource(R.string.form_share),
                    value = state.share,
                    hint = stringResource(R.string.form_share_hint),
                    onValue = { actions.onServerFormChange(state.copy(share = it, error = null)) },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                FormField(
                    label = stringResource(R.string.form_user),
                    value = state.user,
                    hint = stringResource(R.string.form_user_hint),
                    onValue = { actions.onServerFormChange(state.copy(user = it, error = null)) },
                )
                Spacer(Modifier.height(8.dp))
                FormField(
                    label = stringResource(R.string.form_domain),
                    value = state.domain,
                    hint = "WORKGROUP",
                    onValue = { actions.onServerFormChange(state.copy(domain = it, error = null)) },
                )
                Spacer(Modifier.height(8.dp))
                FormField(
                    label = if (state.hasStoredPassword) stringResource(R.string.form_password_keep) else stringResource(R.string.form_password),
                    value = state.password,
                    hint = if (state.hasStoredPassword) stringResource(R.string.form_password_hint) else "••••••••",
                    password = true,
                    onValue = { actions.onServerFormChange(state.copy(password = it, error = null)) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        FormToggle(
            label = stringResource(R.string.form_smb1),
            checked = state.smb1,
            onToggle = { actions.onServerFormChange(state.copy(smb1 = it)) },
        )
        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, style = HuginnTypography.body, color = HuginnColors.Err)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FormButton(stringResource(R.string.form_save), emphasized = true, onClick = actions.onServerFormSave)
            FormButton(stringResource(R.string.form_cancel), onClick = actions.onServerFormCancel)
            if (!isNew) {
                FormButton(
                    label = if (confirmDelete) stringResource(R.string.form_delete_confirm) else stringResource(R.string.form_delete),
                    onClick = {
                        if (confirmDelete) actions.onServerFormDelete() else confirmDelete = true
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.form_keyboard_hint),
            style = HuginnTypography.micro,
            color = HuginnColors.Dim,
        )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    hint: String,
    onValue: (String) -> Unit,
    password: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, style = HuginnTypography.secondary) },
        placeholder = { Text(hint, style = HuginnTypography.secondary, color = HuginnColors.Dim) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun FormToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) HuginnColors.SelBg else HuginnColors.Surface)
            .clickable { onToggle(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (checked) "☑" else "☐", style = HuginnTypography.title, color = HuginnColors.Accent)
        Spacer(Modifier.width(10.dp))
        Text(label, style = HuginnTypography.secondary, color = HuginnColors.Text)
    }
}

@Composable
private fun FormButton(label: String, onClick: () -> Unit, emphasized: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (emphasized) HuginnColors.Accent else HuginnColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = HuginnTypography.body,
            color = if (emphasized) Color.Black else HuginnColors.Text,
        )
    }
}

/**
 * Preparación de un archivo no multimedia (M5): los de SMB se descargan a caché
 * antes de lanzar la app externa arriba. Muestra progreso y permite cancelar con B.
 */
@Composable
private fun ExternalDownloadContent(
    state: TopPanelState.ExternalDownload,
    actions: TopPanelActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📄", fontSize = 72.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            state.name,
            style = HuginnTypography.display,
            color = HuginnColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .width(420.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(HuginnColors.Border),
        ) {
            state.progress?.let { fraction ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(HuginnColors.Accent),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                append(state.message.ifBlank { stringResource(R.string.external_message_default) })
                state.progress?.let { append(" · ${(it * 100).toInt()} %") }
            },
            style = HuginnTypography.body,
            color = HuginnColors.Muted,
        )
        Spacer(Modifier.height(18.dp))
        FormButton(stringResource(R.string.external_cancel), onClick = actions.onCancelExternal)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.external_hint), style = HuginnTypography.micro, color = HuginnColors.Dim)
    }
}

/**
 * Buscador local (M5): el campo vive aquí porque el teclado del sistema solo funciona
 * en la ventana de la Activity (la `Presentation` inferior no es focusable).
 */
@Composable
private fun SearchFormContent(
    state: TopPanelState.SearchForm,
    actions: TopPanelActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.search_title), style = HuginnTypography.display, color = HuginnColors.Text)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.search_in, state.scope), style = HuginnTypography.body, color = HuginnColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = state.term,
            onValueChange = actions.onSearchTermChange,
            singleLine = true,
            label = { Text(stringResource(R.string.search_label), style = HuginnTypography.secondary) },
            modifier = Modifier.width(560.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                state.searching -> stringResource(R.string.searching)
                state.results > 0 -> stringResource(R.string.search_results, state.results)
                state.term.length >= 2 -> stringResource(R.string.search_no_results)
                else -> stringResource(R.string.search_min)
            },
            style = HuginnTypography.body,
            color = HuginnColors.Muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(state.hint, style = HuginnTypography.micro, color = HuginnColors.Dim)
    }
}

/** Aprendizaje de un botón (M5): muestra la acción y espera el keycode. */
@Composable
private fun LearnKeyContent(
    state: TopPanelState.LearnKey,
    actions: TopPanelActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.keymap_title), style = HuginnTypography.display, color = HuginnColors.Text)
        Spacer(Modifier.height(10.dp))
        Text(state.actionLabel, style = HuginnTypography.display, color = HuginnColors.Accent)
        Text(stringResource(R.string.keymap_now, state.currentKey), style = HuginnTypography.body, color = HuginnColors.Muted)
        Spacer(Modifier.height(16.dp))
        Text(
            state.message.ifBlank { stringResource(R.string.keymap_learn) },
            style = HuginnTypography.body,
            color = HuginnColors.Dim,
        )
        Spacer(Modifier.height(18.dp))
        FormButton(stringResource(R.string.form_cancel), onClick = actions.onCancelLearnKey)
    }
}

@Composable
private fun ErrorContent(state: TopPanelState.Error) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠", fontSize = 64.sp)
        Spacer(Modifier.height(12.dp))
        Text(state.message, style = HuginnTypography.title, color = HuginnColors.Err)
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text = text,
        style = HuginnTypography.secondary,
        color = HuginnColors.Text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(HuginnColors.Surface)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun RowDetail(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = HuginnTypography.secondary,
            color = HuginnColors.Dim,
        )
        Text(
            value,
            style = HuginnTypography.secondary,
            color = HuginnColors.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.DIRECTORY -> stringResource(R.string.dir)
    MediaKind.IMAGE -> stringResource(R.string.image)
    MediaKind.AUDIO -> stringResource(R.string.audio)
    MediaKind.VIDEO -> stringResource(R.string.video)
    MediaKind.OTHER -> stringResource(R.string.file)
}

private fun glyphFor(kind: MediaKind): String = when (kind) {
    MediaKind.DIRECTORY -> "📁"
    MediaKind.IMAGE -> "🖼"
    MediaKind.AUDIO -> "🎵"
    MediaKind.VIDEO -> "🎬"
    MediaKind.OTHER -> "📄"
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

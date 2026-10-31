package dev.huginn.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.huginn.core.MediaFacts
import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import dev.huginn.data.MediaKind
import dev.huginn.model.ExplorerView
import dev.huginn.R
import dev.huginn.ui.theme.HuginnColors
import dev.huginn.ui.thumb.FsThumbnail
import dev.huginn.ui.top.TopPanelState
import dev.huginn.ui.theme.HuginnTypography

/**
 * Explorador de la pantalla inferior (spec §5). M1: app bar completa, migas, vistas
 * lista/grid/galería, bottom sheets y paridad táctil. La selección sigue viviendo en
 * el controlador; aquí solo se pinta y se delega en las acciones.
 */
@Composable
fun ExplorerScreen(
    state: ExplorerUiState,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onCycleView: () -> Unit,
    onSort: () -> Unit,
    onMore: () -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onSheetItem: (Int) -> Unit,
    onSheetDismiss: () -> Unit,
    onMiniPlayerToggle: () -> Unit,
    onMiniPlayerFocus: () -> Unit,
    onMiniPlayerScreenOff: () -> Unit,
    onWakeBottomScreen: () -> Unit,
    loadThumbnail: suspend (FsEntry, Int) -> ImageBitmap?,
    /** M4: se incrementa cuando llegan metadatos nuevos (póster/dimensión/tags). */
    factsVersion: Int = 0,
    factsOf: (FsEntry) -> MediaFacts? = { null },
    modifier: Modifier = Modifier,
) {
    // Al tocar no queremos que la lista recentre el elemento (rompía el doble toque);
    // solo se auto-scrollea la navegación con mando cuando el elemento queda fuera.
    val skipScroll = remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(HuginnColors.Panel)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ExplorerAppBar(
                state = state,
                onBack = onBack,
                onUp = onUp,
                onRefresh = onRefresh,
                onCycleView = onCycleView,
                onSort = onSort,
                onMore = onMore,
            )
            BreadcrumbBar(state = state, onBreadcrumb = onBreadcrumb)
            HorizontalDivider(color = HuginnColors.Border, thickness = 1.dp)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.error != null -> ErrorState(state.error, onRetry = onRefresh)
                    state.items.isEmpty() -> EmptyState(state.loading, state.view, state.emptyHint)
                    state.view == ExplorerView.GRID ->
                        GridView(state, onSelect, onAccept, loadThumbnail, skipScroll, factsVersion, factsOf)
                    state.view == ExplorerView.GALLERY ->
                        GalleryView(state, onSelect, onAccept, loadThumbnail, skipScroll)
                    else -> ListView(state, onSelect, onAccept, loadThumbnail, skipScroll)
                }
            }

            (state.topPanel as? TopPanelState.Playing)?.let { playing ->
                MiniPlayer(
                    state = playing,
                    onToggle = onMiniPlayerToggle,
                    onFocus = onMiniPlayerFocus,
                    onScreenOff = onMiniPlayerScreenOff,
                )
            }

            StatusBar(state)
        }

        state.sheet?.let { sheet ->
            SheetOverlay(sheet = sheet, onItem = onSheetItem, onDismiss = onSheetDismiss)
        }

        // Apagado de la pantalla inferior mientras se reproduce vídeo: negro casi
        // total, las teclas siguen controlando el vídeo y un toque la enciende.
        if (state.bottomScreenOff) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onWakeBottomScreen),
            )
        }
    }
}

/**
 * Un toque selecciona **al instante** y un segundo toque rápido (≤320 ms) abre.
 * `combinedClickable` retrasa el click simple hasta descartar el doble toque: eso
 * causaba el retardo táctil reportado.
 */
@Composable
private fun Modifier.tapOrDoubleTap(onTap: () -> Unit, onDoubleTab: () -> Unit): Modifier {
    val lastTap = remember { mutableLongStateOf(0L) }
    return pointerInput(Unit) {
        detectTapGestures(onTap = {
            val now = System.currentTimeMillis()
            if (now - lastTap.longValue in 1..DOUBLE_TAP_MS) {
                lastTap.longValue = 0L
                onDoubleTab()
            } else {
                lastTap.longValue = now
                onTap()
            }
        })
    }
}

private const val DOUBLE_TAP_MS = 320L

// --------------------------------------------------------------------- app bar

@Composable
private fun ExplorerAppBar(
    state: ExplorerUiState,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onCycleView: () -> Unit,
    onSort: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("←", onClick = onBack)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = state.title,
                style = HuginnTypography.title.copy(fontSize = 16.sp),
                color = HuginnColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.subtitle.isNotBlank()) {
                Text(
                    text = state.subtitle,
                    style = HuginnTypography.micro,
                    color = HuginnColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.trail.isNotEmpty()) BarButton("↑", onClick = onUp)
        BarButton("⟳", onClick = onRefresh)
        if (state.location !is FsPath.Roots) {
            BarButton("⇅", onClick = onSort)
            BarButton(viewGlyph(state.view), onClick = onCycleView, accent = state.view != ExplorerView.LIST)
        }
        BarButton("⋯", onClick = onMore)
        if (state.loading) {
            Text("…", color = HuginnColors.Accent, fontSize = 16.sp, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit, accent: Boolean = false) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (accent) HuginnColors.Accent else HuginnColors.Muted, fontSize = 18.sp)
    }
}

private fun viewGlyph(view: ExplorerView): String = when (view) {
    ExplorerView.LIST -> "☰"
    ExplorerView.GRID -> "▦"
    ExplorerView.GALLERY -> "▩"
}

@Composable
private fun BreadcrumbBar(state: ExplorerUiState, onBreadcrumb: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(label = "Huginn", active = state.trail.isEmpty(), onClick = { onBreadcrumb(-1) })
        state.trail.forEachIndexed { index, crumb ->
            Text("›", color = HuginnColors.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
            Crumb(label = crumb.name, active = index == state.trail.lastIndex, onClick = { onBreadcrumb(index) })
        }
    }
}

@Composable
private fun Crumb(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) HuginnColors.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = HuginnTypography.micro,
            color = if (active) HuginnColors.Text else HuginnColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --------------------------------------------------------------------- vistas

@Composable
private fun ListView(
    state: ExplorerUiState,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
    loadThumbnail: suspend (FsEntry, Int) -> ImageBitmap?,
    skipScroll: MutableState<Boolean>,
) {
    val listState = rememberLazyListState()
    val lastLocation = remember { mutableStateOf<dev.huginn.data.FsPath?>(null) }
    LaunchedEffect(state.location, state.items.size) {
        if (lastLocation.value != state.location) {
            lastLocation.value = state.location
            if (state.items.isNotEmpty()) listState.scrollToItem(0)
        }
    }
    LaunchedEffect(state.selectedIndex, state.items.size, state.view) {
        val index = state.selectedIndex
        if (index < 0 || state.items.isEmpty()) return@LaunchedEffect
        // El toque ya coloca el elemento donde está el dedo: no recentrar.
        if (skipScroll.value) {
            skipScroll.value = false
            return@LaunchedEffect
        }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) listState.animateScrollToItem(index)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(state.items, key = { index, item -> "${itemKey(item)}#$index" }) { index, item ->
            ListRow(
                item = item,
                selected = index == state.selectedIndex,
                onClick = {
                    skipScroll.value = true
                    onSelect(index)
                },
                onDoubleClick = {
                    onSelect(index)
                    onAccept()
                },
            )
        }
    }
}

@Composable
private fun ListRow(
    item: ExplorerItem,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (selected) HuginnColors.SelBg else Color.Transparent)
            .tapOrDoubleTap(onTap = onClick, onDoubleTab = onDoubleClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(HuginnColors.Accent),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        ItemGlyph(item = item, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = HuginnTypography.body.copy(fontSize = 14.sp),
                color = if (selected) HuginnColors.Accent else HuginnColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = itemSubtitle(item),
                style = HuginnTypography.micro,
                color = HuginnColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GridView(
    state: ExplorerUiState,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
    loadThumbnail: suspend (FsEntry, Int) -> ImageBitmap?,
    skipScroll: MutableState<Boolean>,
    factsVersion: Int,
    factsOf: (FsEntry) -> MediaFacts?,
) {
    val gridState = rememberLazyGridState()
    val lastLocation = remember { mutableStateOf<dev.huginn.data.FsPath?>(null) }
    LaunchedEffect(state.location, state.items.size) {
        if (lastLocation.value != state.location) {
            lastLocation.value = state.location
            if (state.items.isNotEmpty()) gridState.scrollToItem(0)
        }
    }
    LaunchedEffect(state.selectedIndex, state.view, state.items.size) {
        val index = state.selectedIndex
        if (index < 0 || state.items.isEmpty()) return@LaunchedEffect
        if (skipScroll.value) {
            skipScroll.value = false
            return@LaunchedEffect
        }
        val visible = gridState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) gridState.animateScrollToItem(index)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(state.columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItemsIndexed(state.items, key = { index, item -> "${itemKey(item)}#$index" }) { index, item ->
            GridCell(
                item = item,
                selected = index == state.selectedIndex,
                loadThumbnail = loadThumbnail,
                factsVersion = factsVersion,
                factsOf = factsOf,
                onClick = {
                    skipScroll.value = true
                    onSelect(index)
                },
                onDoubleClick = {
                    skipScroll.value = true
                    onSelect(index)
                    onAccept()
                },
            )
        }
    }
}

@Composable
private fun GridCell(
    item: ExplorerItem,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    loadThumbnail: suspend (FsEntry, Int) -> ImageBitmap?,
    factsVersion: Int,
    factsOf: (FsEntry) -> MediaFacts?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) HuginnColors.SelBg else Color.Transparent)
            .tapOrDoubleTap(onTap = onClick, onDoubleTab = onDoubleClick)
            .padding(4.dp),
    ) {
        val entry = (item as? ExplorerItem.FileItem)?.entry
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(588f / 306f) // miniatura ≈1,92:1 (spec §5.3)
                .clip(RoundedCornerShape(10.dp))
                .background(HuginnColors.Surface2)
                .then(
                    if (selected) Modifier.border(2.dp, HuginnColors.Accent, RoundedCornerShape(10.dp)) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val showPoster = entry != null &&
                (entry.mediaKind == MediaKind.IMAGE || entry.mediaKind == MediaKind.VIDEO)
            if (showPoster) {
                FsThumbnail(
                    entry = entry!!,
                    targetPx = 512,
                    loader = loadThumbnail,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(glyphOf(item), fontSize = 34.sp)
                }
            } else {
                Text(glyphOf(item), fontSize = 34.sp)
            }
            // Chip de duración del vídeo (spec §5.3), cuando ya se conocen metadatos.
            if (entry != null && entry.mediaKind == MediaKind.VIDEO) {
                val facts = remember(factsVersion, entry.path) { factsOf(entry) }
                facts?.durationText?.let { duration ->
                    Text(
                        duration,
                        style = HuginnTypography.micro,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = HuginnTypography.secondary.copy(fontSize = 12.sp),
            color = if (selected) HuginnColors.Accent else HuginnColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
        Text(
            text = itemSubtitle(item),
            style = HuginnTypography.micro.copy(fontSize = 10.sp),
            color = HuginnColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun GalleryView(
    state: ExplorerUiState,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
    loadThumbnail: suspend (FsEntry, Int) -> ImageBitmap?,
    skipScroll: MutableState<Boolean>,
) {
    val gridState = rememberLazyGridState()
    val lastLocation = remember { mutableStateOf<dev.huginn.data.FsPath?>(null) }
    LaunchedEffect(state.location, state.items.size) {
        if (lastLocation.value != state.location) {
            lastLocation.value = state.location
            if (state.items.isNotEmpty()) gridState.scrollToItem(0)
        }
    }
    LaunchedEffect(state.selectedIndex, state.view, state.items.size) {
        val index = state.selectedIndex
        if (index < 0 || state.items.isEmpty()) return@LaunchedEffect
        if (skipScroll.value) {
            skipScroll.value = false
            return@LaunchedEffect
        }
        val visible = gridState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) gridState.animateScrollToItem(index)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(state.columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        gridItemsIndexed(state.items, key = { index, item -> "${itemKey(item)}#$index" }) { index, item ->
            val entry = (item as? ExplorerItem.FileItem)?.entry
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // celda cuadrada (spec §5.3b)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HuginnColors.Surface2)
                    .then(
                        if (index == state.selectedIndex) {
                            Modifier.border(3.dp, HuginnColors.Accent, RoundedCornerShape(10.dp))
                        } else {
                            Modifier
                        },
                    )
                    .tapOrDoubleTap(
                        onTap = {
                            skipScroll.value = true
                            onSelect(index)
                        },
                        onDoubleTab = {
                            skipScroll.value = true
                            onSelect(index)
                            onAccept()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (entry != null && entry.mediaKind == MediaKind.IMAGE) {
                    FsThumbnail(
                        entry = entry,
                        targetPx = 512,
                        loader = loadThumbnail,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(glyphOf(item), fontSize = 30.sp)
                    }
                } else {
                    Text(glyphOf(item), fontSize = 30.sp)
                }
            }
        }
    }
}

// --------------------------------------------------------------------- sheets

@Composable
private fun SheetOverlay(
    sheet: SheetState,
    onItem: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(HuginnColors.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(52.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(HuginnColors.Border),
            )
            Text(
                text = sheet.title,
                style = HuginnTypography.title.copy(fontSize = 17.sp),
                color = HuginnColors.Text,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            sheet.items.forEachIndexed { index, item ->
                val selected = index == sheet.selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) HuginnColors.SelBg else Color.Transparent)
                        .clickable { onItem(index) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        style = HuginnTypography.body.copy(fontSize = 14.sp),
                        color = if (selected) HuginnColors.Accent else HuginnColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.checked) {
                        Text("✓", color = HuginnColors.Accent, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// --------------------------------------------------------------------- estados

@Composable
private fun MiniPlayer(
    state: TopPanelState.Playing,
    onToggle: () -> Unit,
    onFocus: () -> Unit,
    onScreenOff: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(HuginnColors.Surface)
            .clickable(onClick = onFocus)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(HuginnColors.Surface2)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (state.playing) "⏸" else "▶", color = HuginnColors.Text, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.metadata?.title ?: state.name,
                style = HuginnTypography.secondary.copy(fontSize = 12.sp),
                color = HuginnColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.status_playback_mini, miniTime(state.positionMs), miniTime(state.lengthMs)),
                style = HuginnTypography.micro.copy(fontSize = 10.sp),
                color = HuginnColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.isVideo) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(HuginnColors.Surface2)
                    .clickable(onClick = onScreenOff),
                contentAlignment = Alignment.Center,
            ) {
                PowerIcon(color = HuginnColors.Muted)
            }
        } else {
            Text(stringResource(R.string.touch_player_focus), style = HuginnTypography.micro.copy(fontSize = 9.sp), color = HuginnColors.Dim)
        }
    }
}

/** Símbolo de encendido dibujado (el carácter ⏻ sale como cuadrado en esta fuente). */
@Composable
private fun PowerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = 1.6.dp.toPx()
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2, 0f),
            end = Offset(size.width / 2, size.height * 0.55f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun miniTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun StatusBar(state: ExplorerUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HuginnColors.Surface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.status_count, state.items.size, state.sortLabel),
                style = HuginnTypography.micro,
                color = HuginnColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.status_touch),
                style = HuginnTypography.micro,
                color = HuginnColors.Dim,
                maxLines = 1,
            )
        }
        val second = state.notice
        if (!second.isNullOrBlank()) {
            Text(
                text = second,
                style = HuginnTypography.micro,
                color = HuginnColors.Warn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, view: ExplorerView, hint: String? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                hint ?: if (loading) stringResource(R.string.loading) else stringResource(R.string.empty_items),
                color = HuginnColors.Muted,
                style = HuginnTypography.body,
            )
            if (!loading && view == ExplorerView.GALLERY) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.empty_gallery_sub),
                    color = HuginnColors.Dim,
                    style = HuginnTypography.micro,
                )
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠", fontSize = 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, color = HuginnColors.Err, style = HuginnTypography.body)
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(HuginnColors.Surface2)
                .clickable(onClick = onRetry)
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.retry), color = HuginnColors.Text, style = HuginnTypography.body)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.retry_hint), color = HuginnColors.Dim, style = HuginnTypography.micro)
    }
}

// --------------------------------------------------------------------- helpers

@Composable
private fun ItemGlyph(item: ExplorerItem, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(HuginnColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyphOf(item), fontSize = 16.sp)
    }
}

private fun itemKey(item: ExplorerItem): String = when (item) {
    is ExplorerItem.FileItem -> "f:${item.entry.path}"
    is ExplorerItem.ActionItem -> "a:${item.action}"
}

private fun glyphOf(item: ExplorerItem): String = when (item) {
    is ExplorerItem.ActionItem -> "▸"
    is ExplorerItem.FileItem -> when (item.entry.mediaKind) {
        MediaKind.DIRECTORY -> "📁"
        MediaKind.IMAGE -> "🖼"
        MediaKind.AUDIO -> "🎵"
        MediaKind.VIDEO -> "🎬"
        MediaKind.OTHER -> "📄"
    }
}

@Composable
private fun itemSubtitle(item: ExplorerItem): String = when (item) {
    is ExplorerItem.ActionItem -> item.subtitle
    is ExplorerItem.FileItem -> {
        val entry = item.entry
        when {
            entry.isDirectory -> stringResource(R.string.dir)
            entry.size != null -> "${kindLabel(entry.mediaKind)} · ${formatSize(entry.size)}"
            else -> kindLabel(entry.mediaKind)
        }
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

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

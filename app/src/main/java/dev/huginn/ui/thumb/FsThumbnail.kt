package dev.huginn.ui.thumb

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import dev.huginn.data.FsEntry

/**
 * Carga asíncrona de una miniatura de imagen (cualquier origen) con placeholder
 * mientras llega. La carga real la hace `ThumbnailStore` (LRU + límite de
 * concurrencia); aquí solo se pide y se pinta.
 */
@Composable
fun FsThumbnail(
    entry: FsEntry,
    targetPx: Int,
    loader: suspend (FsEntry, Int) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    val image by produceState<ImageBitmap?>(initialValue = null, entry.path, targetPx) {
        value = loader(entry, targetPx)
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = entry.name,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { fallback() }
    }
}

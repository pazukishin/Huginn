package dev.huginn.core

import android.util.Log
import dev.huginn.data.FsEntry
import java.io.File
import java.util.Properties

/**
 * Recientes y favoritos (M5) en un fichero privado de la app.
 *
 * - **Recientes**: máx. [MAX_RECENTS], sin duplicados (se mueve al frente).
 * - **Favoritos**: máx. [MAX_FAVORITES], alternables desde el menú.
 * - Escritura atómica en cada cambio; sin credenciales ni secretos.
 */
class PlacesStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    fun recents(): List<PathRef> = synchronized(lock) { lists().first }

    fun favorites(): List<PathRef> = synchronized(lock) { lists().second }

    fun isFavorite(entry: FsEntry): Boolean = favorites().any { it.path == entry.path }

    /** Añade a recientes (deduplicado, al frente) y persiste. */
    fun recordRecent(entry: FsEntry) {
        synchronized(lock) {
            val (recents, favorites) = lists()
            val ref = PathRefCodec.fromEntry(entry, clock())
            val updated = (listOf(ref) + recents.filterNot { it.path == entry.path })
                .take(MAX_RECENTS)
            write(updated, favorites)
        }
    }

    /** Añade o quita de favoritos; devuelve si queda como favorito. */
    fun toggleFavorite(entry: FsEntry): Boolean {
        synchronized(lock) {
            val (recents, favorites) = lists()
            val existing = favorites.any { it.path == entry.path }
            val updated = if (existing) {
                favorites.filterNot { it.path == entry.path }
            } else {
                (listOf(PathRefCodec.fromEntry(entry, clock())) + favorites).take(MAX_FAVORITES)
            }
            write(recents, updated)
            return !existing
        }
    }

    private fun lists(): Pair<List<PathRef>, List<PathRef>> {
        if (!file.isFile) return emptyList<PathRef>() to emptyList()
        return runCatching {
            val props = Properties()
            file.reader(Charsets.UTF_8).use { props.load(it) }
            if (props.getProperty("version")?.toIntOrNull() != VERSION) {
                return@runCatching emptyList<PathRef>() to emptyList()
            }
            readList(props, "recents") to readList(props, "favorites")
        }.onFailure { Log.w(TAG, "no se pudieron leer recientes/favoritos", it) }
            .getOrDefault(emptyList<PathRef>() to emptyList())
    }

    private fun readList(props: Properties, prefix: String): List<PathRef> {
        val count = props.getProperty("$prefix.count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val base = "$prefix.$index."
            val path = props.getProperty(base + "path")?.let(PathRefCodec::decodePath)
                ?: return@mapNotNull null
            PathRef(
                path = path,
                name = props.getProperty(base + "name").orEmpty(),
                isDirectory = props.getProperty(base + "dir")?.toBooleanStrictOrNull() ?: false,
                addedAt = props.getProperty(base + "added")?.toLongOrNull() ?: 0L,
            )
        }
    }

    private fun write(recents: List<PathRef>, favorites: List<PathRef>) {
        runCatching {
            val props = Properties()
            props.setProperty("version", VERSION.toString())
            putList(props, "recents", recents)
            putList(props, "favorites", favorites)
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writer(Charsets.UTF_8).use { props.store(it, "Huginn recents/favorites") }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "no se pudieron guardar recientes/favoritos", it) }
    }

    private fun putList(props: Properties, prefix: String, list: List<PathRef>) {
        props.setProperty("$prefix.count", list.size.toString())
        list.forEachIndexed { index, ref ->
            val base = "$prefix.$index."
            props.setProperty(base + "path", PathRefCodec.encodePath(ref.path))
            props.setProperty(base + "name", ref.name)
            props.setProperty(base + "dir", ref.isDirectory.toString())
            props.setProperty(base + "added", ref.addedAt.toString())
        }
    }

    private companion object {
        const val TAG = "HuginnPlaces"
        const val VERSION = 1
        const val MAX_RECENTS = 30
        const val MAX_FAVORITES = 50
    }
}

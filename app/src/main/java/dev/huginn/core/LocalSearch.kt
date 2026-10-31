package dev.huginn.core

import dev.huginn.data.FileSource
import dev.huginn.data.FsEntry
import dev.huginn.data.FsPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Búsqueda local recursiva (M5), acotada para no castigar al NAS ni al dispositivo.
 * Pura respecto a la UI: recibe el [FileSource] y devuelve coincidencias.
 */
object LocalSearch {

    data class Limits(
        val maxDepth: Int = 6,
        val maxEntries: Int = 5_000,
        val maxResults: Int = 200,
    )

    suspend fun find(
        base: FsPath,
        source: FileSource,
        term: String,
        limits: Limits = Limits(),
    ): List<FsEntry> {
        val needle = term.trim()
        if (needle.isEmpty()) return emptyList()
        val results = mutableListOf<FsEntry>()
        val queue = ArrayDeque<Pair<FsPath, Int>>()
        queue.add(base to 0)
        var visited = 0
        while (queue.isNotEmpty() &&
            visited < limits.maxEntries &&
            results.size < limits.maxResults
        ) {
            currentCoroutineContext().ensureActive()
            val (path, depth) = queue.removeFirst()
            val entries = runCatching { source.list(path) }.getOrElse { continue }
            for (entry in entries) {
                visited++
                if (entry.name.contains(needle, ignoreCase = true)) results.add(entry)
                if (entry.isDirectory && depth < limits.maxDepth) queue.add(entry.path to depth + 1)
                if (results.size >= limits.maxResults || visited >= limits.maxEntries) break
            }
        }
        return results
    }
}

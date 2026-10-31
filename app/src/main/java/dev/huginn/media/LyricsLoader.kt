package dev.huginn.media

/**
 * Localiza y carga la letra de un audio (spec §4.5):
 * 1. `.lrc` con el mismo nombre junto al fichero → sincronizada;
 * 2. `.txt` con el mismo nombre → estática;
 * 3. variantes de idioma (`tema.es.lrc`) como respaldo.
 *
 * La lectura se inyecta (`read`) para poder testear sin Android y reutilizarse con
 * SAF (content://) y SMB (jcifs-ng).
 */
object LyricsLoader {

    /** Nombres candidatos dentro de la misma carpeta, en orden de preferencia. */
    fun candidates(audioName: String, siblings: List<String>): List<String> {
        val base = audioName.substringBeforeLast('.', audioName).lowercase()
        fun rank(name: String): Int = when {
            name.lowercase() == "$base.lrc" -> 0
            name.lowercase() == "$base.txt" -> 1
            name.lowercase().startsWith("$base.") && name.lowercase().endsWith(".lrc") -> 2
            name.lowercase().startsWith("$base.") && name.lowercase().endsWith(".txt") -> 3
            else -> Int.MAX_VALUE
        }
        return siblings
            .filter { it.lowercase().substringBeforeLast('.') != audioName.lowercase() }
            .filter { it.lowercase().endsWith(".lrc") || it.lowercase().endsWith(".txt") }
            .filter { rank(it) != Int.MAX_VALUE }
            .sortedWith(compareBy<String>({ rank(it) }, { it.lowercase() }))
    }

    /** Carga la primera letra disponible; `null` si no hay ninguna legible. */
    suspend fun load(
        audioName: String,
        siblings: List<String>,
        read: suspend (name: String) -> ByteArray?,
    ): Lyrics? {
        for (candidate in candidates(audioName, siblings)) {
            val bytes = runCatching { read(candidate) }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            val lyrics = LyricsParser.parse(bytes, candidate)
            if (!lyrics.isEmpty) return lyrics
        }
        return null
    }
}

package dev.huginn.media

import dev.huginn.data.MimeTypes

/**
 * Detección de subtítulos externos junto al fichero de vídeo (spec §4.4):
 * mismos nombre y `.srt/.ass/.ssa/.vtt/.sub`, incluidas variantes de idioma del tipo
 * `peli.es.srt` o `peli.forced.ass`. Sin Android: testeable en JVM.
 */
object SubtitleMatcher {

    fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    fun isSubtitle(fileName: String): Boolean = MimeTypes.isSubtitle(fileName)

    /**
     * Subtítulos externos que acompañan a [videoName] dentro de [siblings]
     * (nombres de la misma carpeta, sin rutas). Orden: coincidencia exacta de base
     * primero y luego alfabético.
     */
    fun externalFor(videoName: String, siblings: List<String>): List<String> {
        val base = baseName(videoName).lowercase()
        return siblings
            .filter { sibling -> sibling != videoName && isSubtitle(sibling) }
            .filter { sibling ->
                val siblingBase = baseName(sibling).lowercase()
                siblingBase == base || siblingBase.startsWith("$base.")
            }
            .sortedWith(compareBy<String> { if (baseName(it).lowercase() == base) 0 else 1 }.thenBy { it.lowercase() })
    }
}

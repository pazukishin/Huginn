package dev.huginn.media

/**
 * Nombres de las pistas de subtítulos que libVLC añade como *slaves* (`.srt` externos).
 *
 * libVLC publica los subtítulos externos en `spuTracks` con un nombre genérico
 * («Track 16», «Track 17»…) porque no tienen metadatos de idioma. Para que el menú
 * de pistas muestre el fichero real, se sustituye el nombre de las últimas pistas
 * genéricas por el nombre del `.srt` añadido (libVLC los apenda al final, en orden).
 *
 * Sin dependencias de Android: testeable en JVM.
 */
object SlaveTrackNames {

    private val GENERIC = Regex("^Track \\d+$", RegexOption.IGNORE_CASE)

    /**
     * Devuelve las pistas con los nombres de los slaves aplicados. Si no hay slaves,
     * o no se encuentran suficientes pistas genéricas (no se cargaron todos), deja
     * la lista tal cual para no etiquetar mal pistas incrustadas.
     *
     * @param tracks pares `id` → nombre tal cual los da libVLC.
     * @param slaveNames nombres de fichero de los subtítulos externos, en orden.
     */
    fun relabel(tracks: List<Pair<Int, String>>, slaveNames: List<String>): List<Pair<Int, String>> {
        if (slaveNames.isEmpty()) return tracks
        val generic = tracks.indices.filter { GENERIC.matches(tracks[it].second.trim()) }
        if (generic.size < slaveNames.size) return tracks
        val relabeled = generic.takeLast(slaveNames.size).zip(slaveNames).toMap()
        return tracks.mapIndexed { index, (id, name) -> relabeled[index]?.let { id to it } ?: (id to name) }
    }
}

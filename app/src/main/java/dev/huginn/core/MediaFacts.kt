package dev.huginn.core

import java.util.Properties

/**
 * Metadatos "caros" de un medio que conviene cachear (M4): duración y resolución de
 * vídeo, dimensiones de imagen y etiquetas de audio. Nada de secretos ni rutas
 * completas: la clave del caché va aparte.
 */
data class MediaFacts(
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val mimeType: String? = null,
) {

    val isEmpty: Boolean
        get() = durationMs == null && width == null && height == null &&
            title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()

    /** «1:23:45» / «3:07»; null si no hay duración. */
    val durationText: String?
        get() = durationMs?.let { ms ->
            if (ms <= 0) return null
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

    /** «1920×1080»; null si faltan dimensiones. */
    val resolutionText: String?
        get() {
            val w = width ?: return null
            val h = height ?: return null
            return "$w×$h"
        }

    val tagsText: String?
        get() = listOfNotNull(title, artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { null }
}

/**
 * Serialización de [MediaFacts] en `Properties` versionado, una entrada por medio.
 * Separada del caché para poder testearla en JVM sin Android.
 */
object MediaFactsCodec {

    const val VERSION = 2

    /**
     * Separador visible entre campos base64. Se evita cualquier carácter de control
     * (el `Properties` de Android perdía el `\u001f` al releer el fichero).
     */
    private const val SEP = "|"

    fun encode(entries: Map<String, MediaFacts>): String {
        val props = Properties()
        props.setProperty("version", VERSION.toString())
        entries.forEach { (key, facts) ->
            // `key` ya es un identificador estable (el caché guarda hashes).
            props.setProperty("facts.$key", serialize(facts))
        }
        return java.io.StringWriter().also { props.store(it, "Huginn media facts (caché)") }.toString()
    }

    fun decode(text: String): Map<String, MediaFacts> {
        if (text.isBlank()) return emptyMap()
        val props = runCatching { Properties().apply { java.io.StringReader(text).use { load(it) } } }
            .getOrNull() ?: return emptyMap()
        if (props.getProperty("version")?.toIntOrNull() != VERSION) return emptyMap()
        return props.stringPropertyNames()
            .filter { it.startsWith("facts.") }
            .mapNotNull { name ->
                val facts = deserialize(props.getProperty(name).orEmpty()) ?: return@mapNotNull null
                name.removePrefix("facts.") to facts
            }
            .toMap()
    }

    fun hash(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    internal fun serialize(facts: MediaFacts): String = listOf(
        facts.durationMs?.toString().orEmpty(),
        facts.width?.toString().orEmpty(),
        facts.height?.toString().orEmpty(),
        facts.title.orEmpty(),
        facts.artist.orEmpty(),
        facts.album.orEmpty(),
        facts.mimeType.orEmpty(),
    ).joinToString(SEP) { field ->
        java.util.Base64.getEncoder().encodeToString(field.toByteArray(Charsets.UTF_8))
    }

    internal fun deserialize(value: String): MediaFacts? {
        val parts = value.split(SEP)
        if (parts.size != 7) return null
        fun text(index: Int): String? {
            val encoded = parts[index]
            if (encoded.isEmpty()) return null
            return runCatching {
                String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        return MediaFacts(
            durationMs = text(0)?.toLongOrNull(),
            width = text(1)?.toIntOrNull(),
            height = text(2)?.toIntOrNull(),
            title = text(3),
            artist = text(4),
            album = text(5),
            mimeType = text(6),
        )
    }
}

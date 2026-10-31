package dev.huginn.data

/** Clasificación de lo que la app sabe abrir o previsualizar por sí misma. */
enum class MediaKind {
    DIRECTORY,
    IMAGE,
    AUDIO,
    VIDEO,
    OTHER;

    val isMedia: Boolean
        get() = this == IMAGE || this == AUDIO || this == VIDEO

    companion object {
        fun from(mimeType: String?, name: String, isDirectory: Boolean): MediaKind {
            if (isDirectory) return DIRECTORY
            val mime = mimeType ?: MimeTypes.guess(name)
            return when {
                mime == null -> OTHER
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("audio/") -> AUDIO
                mime.startsWith("video/") -> VIDEO
                else -> OTHER
            }
        }
    }
}

/**
 * Mapa extensión → MIME. Necesario porque SMB no siempre informa del MIME y SAF
 * puede devolver `application/octet-stream` para formatos poco comunes.
 * Ampliable: es la lista que crecerá con los códecs que se vayan probando.
 */
object MimeTypes {

    private val byExtension: Map<String, String> = buildMap {
        fun add(mime: String, vararg exts: String) {
            exts.forEach { put(it, mime) }
        }

        add("image/jpeg", "jpg", "jpeg", "jpe")
        add("image/png", "png")
        add("image/webp", "webp")
        add("image/gif", "gif")
        add("image/bmp", "bmp")
        add("image/heic", "heic", "heif")
        add("image/avif", "avif")
        add("image/svg+xml", "svg")
        add("image/tiff", "tif", "tiff")

        add("audio/mpeg", "mp3")
        add("audio/flac", "flac")
        add("audio/ogg", "ogg", "oga", "opus")
        add("audio/wav", "wav")
        add("audio/x-m4a", "m4a", "aac")
        add("audio/x-ms-wma", "wma")
        add("audio/x-ape", "ape")
        add("audio/x-dsf", "dsf", "dff")
        add("audio/midi", "mid", "midi")

        add("video/mp4", "mp4", "m4v")
        add("video/x-matroska", "mkv")
        add("video/webm", "webm")
        add("video/x-msvideo", "avi")
        add("video/quicktime", "mov")
        add("video/mp2t", "ts", "m2ts")
        add("video/x-ms-wmv", "wmv")
        add("video/x-flv", "flv")
        add("video/mpeg", "mpg", "mpeg", "vob")

        add("text/plain", "txt", "log", "md", "nfo")
        add("text/plain", "lrc")
        add("application/pdf", "pdf")
        add("application/zip", "zip")
        add("application/x-7z-compressed", "7z")
        add("application/x-rar-compressed", "rar")
        add("application/epub+zip", "epub")
        add("application/json", "json")
        add("application/xml", "xml")
    }

    private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub")

    fun guess(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return byExtension[ext]
    }

    fun isSubtitle(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in subtitleExtensions
}

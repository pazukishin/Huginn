package dev.huginn.external

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import dev.huginn.display.currentDisplayId
import dev.huginn.data.FsEntry
import dev.huginn.data.MimeTypes

/**
 * Lanzador de archivos "no multimedia" con apps externas **siempre en la pantalla de
 * contenido** (requisito 1.5 y ADR 6 del plan).
 *
 * El display del llamante es un hint más fuerte que `launchDisplayId`, y las apps
 * `singleTask` (VLC, por ejemplo) pueden ignorarlo. Por eso el lanzamiento a otro
 * display pasa por `TrampolineActivity`, ya colocada allí con
 * `ActivityOptions.launchDisplayId`: desde ella, el destino hereda la pantalla.
 */
class ExternalLauncher(private val app: Application) {

    sealed interface Result {
        data object Launched : Result
        data class NoApp(val mime: String) : Result
        data class Unavailable(val message: String) : Result
        data class Error(val message: String) : Result
    }

    fun open(
        activity: Activity,
        entry: FsEntry,
        contentUri: Uri?,
        contentDisplayId: Int,
        packageName: String? = null,
    ): Result {
        if (contentUri == null) {
            return Result.Unavailable(app.getString(dev.huginn.R.string.external_prepare_failed))
        }
        val mime = entry.mimeType ?: MimeTypes.guess(entry.name) ?: "*/*"
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { packageName?.let { setPackage(it) } }

        val resolver = activity.packageManager
        val resolved = resolver.resolveActivity(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: resolver.resolveActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        if (resolved == null) return Result.NoApp(mime)
        if (resolved.activityInfo?.packageName == activity.packageName) return Result.NoApp(mime)

        return try {
            if (activity.currentDisplayId() != contentDisplayId) {
                pendingIntent = viewIntent
                val trampoline = Intent(activity, TrampolineActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val options = ActivityOptions.makeBasic()
                    .apply { launchDisplayId = contentDisplayId }
                    .toBundle()
                activity.startActivity(trampoline, options)
            } else {
                activity.startActivity(viewIntent)
            }
            Result.Launched
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo lanzar la app externa", t)
            Result.Error(t.message ?: app.getString(dev.huginn.R.string.external_launcher_error))
        }
    }

    private companion object {
        const val TAG = "HuginnExternal"

        /** Intent pendiente que consume la TrampolineActivity ya colocada arriba. */
        @Volatile
        var pendingIntent: Intent? = null

        fun consumePendingIntent(): Intent? = pendingIntent.also { pendingIntent = null }
    }

    /** La llama `TrampolineActivity` desde la pantalla de contenido. */
    fun launchPending(activity: Activity) {
        val intent = consumePendingIntent() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { Log.e(TAG, "fallo en el trampolín", it) }
    }
}

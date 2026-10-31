package dev.huginn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/** Datos de build/diagnóstico sin depender de BuildConfig (desactivado por defecto en AGP 9). */
object BuildInfo {

    fun isDebug(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun describe(context: Context): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
}

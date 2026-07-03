// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun AI tutor (mobile) — thin client for the Speedrun tutor proxy
// (speedrun/proxy). The OpenAI key lives ONLY on that server, never in the APK,
// so users cannot extract it. The client sends grounded context
// (problem/choices/answer/solution/source + chosen); the server builds the
// prompt and calls OpenAI. Graceful degradation: an unreachable server -> a
// friendly message; scores never depend on AI.

package com.ichi2.anki

import android.content.Context
import androidx.preference.PreferenceManager
import com.ichi2.anki.libanki.Collection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object SpeedrunTutor {
    private const val PREF_URL = "speedrunProxyUrl"
    private const val TUTOR_PATH = "/tutor"
    private const val ERROR = "The tutor is unavailable right now. Please try again in a moment."

    /** Server URL: a device-local override wins, else the build-time default. */
    fun proxyUrl(ctx: Context): String {
        val override = PreferenceManager.getDefaultSharedPreferences(ctx).getString(PREF_URL, null)
        val base = if (override.isNullOrBlank()) BuildConfig.SPEEDRUN_PROXY_URL else override.trim()
        return base.trimEnd('/')
    }

    fun setProxyUrl(
        ctx: Context,
        url: String,
    ) {
        PreferenceManager
            .getDefaultSharedPreferences(ctx)
            .edit()
            .putString(PREF_URL, url.trim())
            .apply()
    }

    /** Build grounding context from the card's fields; null if the card is gone. */
    fun buildContext(
        col: Collection,
        cid: Long,
        chosen: String,
    ): JSONObject? {
        val note =
            try {
                col.getCard(cid).note(col)
            } catch (e: Exception) {
                return null
            }

        fun f(n: String): String =
            try {
                note.getItem(n)
            } catch (e: Exception) {
                ""
            }
        val choices = StringBuilder()
        for (l in listOf("A", "B", "C", "D", "E")) {
            val v = f("Choice$l")
            if (v.isNotBlank()) choices.append("$l. $v\n")
        }
        return JSONObject()
            .put("problem", f("Problem"))
            .put("choices", choices.toString())
            .put("answer", f("Answer"))
            .put("solution", f("Solution"))
            .put("source", "${f("Contest")} ${f("Year")} ${f("Topic")}".trim())
            .put("answered", true)
            .put("chosen", chosen)
    }

    /** One grounded turn via the proxy. Runs off the main thread; never throws. */
    suspend fun ask(
        ctx: Context,
        context: JSONObject,
        kind: String,
        text: String,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                post(ctx, context, kind, text)
            } catch (e: Exception) {
                Timber.w(e, "Speedrun tutor error")
                ERROR
            }
        }

    private fun post(
        ctx: Context,
        context: JSONObject,
        kind: String,
        text: String,
    ): String {
        val body =
            JSONObject()
                .put("context", context)
                .put("kind", kind)
                .put("text", text)
                .put("history", JSONArray())
                .toString()
        val conn = URL(proxyUrl(ctx) + TUTOR_PATH).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 35000
            conn.readTimeout = 35000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.SPEEDRUN_PROXY_TOKEN.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SPEEDRUN_PROXY_TOKEN}")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader().use(BufferedReader::readText)
            if (code !in 200..299) {
                Timber.w("Speedrun tutor HTTP %d: %s", code, resp.take(200))
                return ERROR
            }
            return JSONObject(resp).optString("reply", ERROR)
        } finally {
            conn.disconnect()
        }
    }
}

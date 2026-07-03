// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun Practice Test (mobile) — a bespoke test-taking screen, NOT the Anki
// reviewer. Mirrors the desktop qt/aqt/speedrun_test.py: a full-screen WebView
// shows one question at a time with numeric progress + a countdown (one pass),
// then an in-screen review of the missed ones with worked solutions. It never
// touches decks/scheduler/review pile. First-attempt correctness is saved to
// card.custom_data for the Performance score.

package com.ichi2.anki

import android.annotation.SuppressLint
import android.app.Dialog
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.snackbar.showSnackbar
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private const val SR_SEARCH = "deck:Speedrun::*"
private const val SR_QUESTIONS = 20
private const val SR_MINUTES = 40

// LaTeX text commands MathJax ignores -> render as HTML (for decks built before
// the builder handled these; freshly built decks are already clean).
private val SR_TEXT_CMDS =
    listOf(
        "emph" to Pair("<em>", "</em>"),
        "textbf" to Pair("<b>", "</b>"),
        "textit" to Pair("<i>", "</i>"),
        "underline" to Pair("<u>", "</u>"),
        "texttt" to Pair("<code>", "</code>"),
        "textrm" to Pair("", ""),
        "textsf" to Pair("", ""),
        "textsc" to Pair("", ""),
    )

private fun srClean(text: String): String {
    if (!text.contains("\\")) return text
    var out = text
    for ((cmd, tags) in SR_TEXT_CMDS) {
        out = Regex("\\\\" + cmd + "\\{([^{}]*)\\}").replace(out) { tags.first + it.groupValues[1] + tags.second }
    }
    return out
}

/** Gather up to SR_QUESTIONS random Speedrun questions as a JSON payload, or
 * null if there are none. Skips figure ([asy]) problems we can't render. */
private fun buildSpeedrunPayload(col: Collection): String? {
    val cids = col.findCards(SR_SEARCH).shuffled()
    val arr = JSONArray()
    for (cid in cids) {
        if (arr.length() >= SR_QUESTIONS) break
        val note =
            try {
                col.getCard(cid).note(col)
            } catch (e: Exception) {
                continue
            }

        fun f(name: String): String =
            try {
                note.getItem(name)
            } catch (e: Exception) {
                ""
            }
        val problem = f("Problem")
        val solution = f("Solution")
        val choices = JSONArray()
        for (l in listOf("A", "B", "C", "D", "E")) {
            val v = f("Choice$l")
            if (v.isNotBlank()) choices.put(JSONArray().put(l).put(v))
        }
        if (problem.isBlank() || choices.length() < 2) continue
        val blob = (problem + " " + solution).lowercase()
        if (blob.contains("[asy]") || blob.contains("[/asy]")) continue
        val cleanChoices = JSONArray()
        for (i in 0 until choices.length()) {
            val c = choices.getJSONArray(i)
            cleanChoices.put(JSONArray().put(c.getString(0)).put(srClean(c.getString(1))))
        }
        arr.put(
            JSONObject()
                .put("cid", cid)
                .put("meta", "${f("Contest")} ${f("Year")} · Problem ${f("Number")} · ${f("Topic")}")
                .put("problem", srClean(problem))
                .put("choices", cleanChoices)
                .put("answer", f("Answer").trim())
                .put("solution", srClean(solution)),
        )
    }
    if (arr.length() == 0) return null
    return JSONObject().put("questions", arr).put("minutes", SR_MINUTES).toString()
}

private class SpeedrunBridge(
    val dp: DeckPicker,
    val dialog: Dialog,
    val web: WebView,
) {
    @JavascriptInterface
    fun cmd(s: String) {
        // Note: per-card correctness is not persisted to custom_data on mobile
        // yet (Card.customData has a private setter here); the test + review run
        // fully. Desktop persists it for the Performance score.
        if (s == "sr:close") {
            dp.runOnUiThread {
                dialog.dismiss()
                dp.updateDeckList()
            }
        } else if (s.startsWith("sr:tutor:")) {
            handleTutor(s.removePrefix("sr:tutor:"))
        } else if (s.startsWith("sr:record:")) {
            Timber.d("Speedrun: %s", s)
        }
    }

    private fun reply(
        role: String,
        html: String,
    ) {
        val js = "srTutorReply(${JSONObject.quote(role)}, ${JSONObject.quote(html)});"
        dp.runOnUiThread { web.evaluateJavascript(js, null) }
    }

    // Review-phase AI tutor. Message: <kind>:<cid>:<chosen>:<url-encoded text?>
    private fun handleTutor(rest: String) {
        val parts = rest.split(":", limit = 4)
        val kind = parts.getOrNull(0) ?: return
        val cid = parts.getOrNull(1)?.toLongOrNull() ?: return
        val chosen = parts.getOrNull(2) ?: ""
        val text = parts.getOrNull(3)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        reply("thinking", "\u2026")
        dp.runOnUiThread {
            dp.launchCatchingTask {
                val context = withCol { SpeedrunTutor.buildContext(this, cid, chosen) }
                if (context == null) return@launchCatchingTask
                reply("tutor", SpeedrunTutor.ask(dp, context, kind, text))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun DeckPicker.startSpeedrunPracticeTest() {
    launchCatchingTask {
        val payload = withCol { buildSpeedrunPayload(this) }
        if (payload == null) {
            showSnackbar("No Speedrun questions found — add a Speedrun deck first")
            return@launchCatchingTask
        }
        val html =
            assets
                .open("speedrun_test.html")
                .bufferedReader()
                .use { it.readText() }
                .replace("__SR_DATA__", payload)
        val web = WebView(this@startSpeedrunPracticeTest)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val dialog = Dialog(this@startSpeedrunPracticeTest, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(web)
        web.addJavascriptInterface(SpeedrunBridge(this@startSpeedrunPracticeTest, dialog, web), "AndroidSpeedrun")
        // https base origin so the CDN MathJax script loads.
        web.loadDataWithBaseURL("https://speedrun.local/", html, "text/html", "utf-8", null)
        dialog.setOnDismissListener { web.destroy() }
        dialog.show()
        Timber.i("Speedrun: practice test opened")
    }
}

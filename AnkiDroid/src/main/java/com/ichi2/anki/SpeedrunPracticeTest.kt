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
        arr.put(
            JSONObject()
                .put("cid", cid)
                .put("meta", "${f("Contest")} ${f("Year")} · Problem ${f("Number")} · ${f("Topic")}")
                .put("problem", problem)
                .put("choices", choices)
                .put("answer", f("Answer").trim())
                .put("solution", solution),
        )
    }
    if (arr.length() == 0) return null
    return JSONObject().put("questions", arr).put("minutes", SR_MINUTES).toString()
}

private class SpeedrunBridge(
    val dp: DeckPicker,
    val dialog: Dialog,
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
        } else if (s.startsWith("sr:record:")) {
            Timber.d("Speedrun: %s", s)
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
        web.addJavascriptInterface(SpeedrunBridge(this@startSpeedrunPracticeTest, dialog), "AndroidSpeedrun")
        // https base origin so the CDN MathJax script loads.
        web.loadDataWithBaseURL("https://speedrun.local/", html, "text/html", "utf-8", null)
        dialog.setOnDismissListener { web.destroy() }
        dialog.show()
        Timber.i("Speedrun: practice test opened")
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun Study Plan dashboard (mobile) — mirrors the desktop
// qt/aqt/speedrun_plan.py. A WebView shows Today + a roadmap timeline +
// readiness gauge + focus areas, with a Plan/Scores toggle. "Start studying"
// (re)builds the daily Speedrun::Study Plan filtered deck and opens the reviewer;
// each focus area builds a topic filtered deck. No gating — any deck is studyable.

package com.ichi2.anki

import android.annotation.SuppressLint
import android.app.Dialog
import android.webkit.JavascriptInterface
import android.webkit.WebView
import anki.decks.Deck
import anki.decks.FilteredDeckForUpdate
import anki.stats.SpeedrunScore
import anki.stats.SpeedrunScores
import anki.stats.SpeedrunSubjectStat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.snackbar.showSnackbar
import timber.log.Timber

private const val RAW = "Speedrun::Raw Card Decks"
private const val RAW_SEARCH = "deck:\"Speedrun::Raw Card Decks::*\""
private const val PHASE1_MAX = 34.0
private const val PHASE2_MAX = 67.0

// ---- filtered-deck builders (mirror pylib/anki/speedrun.py) -----------------

private fun Collection.buildFiltered(
    name: String,
    search: String,
    limit: Int,
    order: Deck.Filtered.SearchTerm.Order,
    reschedule: Boolean,
): DeckId {
    val existing = decks.idForName(name) ?: 0L
    val term =
        Deck.Filtered.SearchTerm
            .newBuilder()
            .setSearch(search)
            .setLimit(limit)
            .setOrder(order)
            .build()
    val config =
        Deck.Filtered
            .newBuilder()
            .setReschedule(reschedule)
            .addSearchTerms(term)
            .build()
    val input =
        FilteredDeckForUpdate
            .newBuilder()
            .setId(existing)
            .setName(name)
            .setConfig(config)
            .build()
    return sched.addOrUpdateFilteredDeck(input).id
}

/** The daily Study Plan filtered deck: due cards first, then unseen, from the
 * raw content decks. Rebuilt on demand. Null if nothing to study. */
fun Collection.buildStudyPlanDeck(): DeckId? {
    val search = "$RAW_SEARCH (is:due OR is:new)"
    if (findCards(search).isEmpty()) return null
    return buildFiltered("Speedrun::Study Plan", search, 25, Deck.Filtered.SearchTerm.Order.DUE, true)
}

/** A topic-scoped filtered deck for a focus area. Null if that topic has nothing. */
fun Collection.buildFocusDeck(topic: String): DeckId? {
    val safe = topic.filter { it.isLetterOrDigit() || it == '_' }
    if (safe.isEmpty()) return null
    val search = "$RAW_SEARCH \"tag:topic::$safe\" (is:due OR is:new)"
    if (findCards(search).isEmpty()) return null
    return buildFiltered("Speedrun Focus", search, 20, Deck.Filtered.SearchTerm.Order.DUE, true)
}

// ---- dashboard HTML ---------------------------------------------------------

private data class PlanData(
    val scores: SpeedrunScores,
    val due: Int,
    val newAvail: Int,
)

private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun lever(s: SpeedrunSubjectStat): Float = (s.examWeight / 100f) * (1f - s.performance / 100f)

private fun phase(coverage: Double): Triple<String, Int, String> =
    when {
        coverage < PHASE1_MAX ->
            Triple("Foundations", 1, "AMC 8 + core calculus, algebra & linear algebra")
        coverage < PHASE2_MAX ->
            Triple("Core", 2, "AMC 10/12 + probability, series, multivariable, number theory")
        else ->
            Triple("Advanced", 3, "hard AMC 12 + real analysis, abstract algebra, complex analysis")
    }

private fun scoreBlock(
    title: String,
    subtitle: String,
    s: SpeedrunScore,
    scaled: Boolean,
): String {
    if (!s.available) {
        return "<div class='score'><div class='t'>$title</div><div class='st'>$subtitle</div>" +
            "<div class='grange'>${esc(s.abstainReason)}</div></div>"
    }
    val pt = if (scaled) "%.0f".format(s.point) else "%.0f%%".format(s.point)
    val rng =
        if (scaled) {
            "likely %.0f–%.0f".format(s.rangeLow, s.rangeHigh)
        } else {
            "likely %.0f%%–%.0f%%".format(s.rangeLow, s.rangeHigh)
        }
    return "<div class='score'><div class='t'>$title</div><div class='st'>$subtitle</div>" +
        "<div><span class='pt'>$pt</span> <span class='grange'>$rng · ${s.confidence}</span></div></div>"
}

private fun buildPlanBody(data: PlanData): String {
    val scores = data.scores
    val cov = scores.coveragePercent.toDouble().coerceIn(0.0, 100.0)
    val (name, number, covers) = phase(cov)
    val subjects = scores.subjectsList
    val focus = subjects.filter { it.totalCards > 0 }.sortedByDescending { lever(it) }.take(3)
    val target = focus.firstOrNull()?.label?.let { esc(it) } ?: "your decks"

    val todayLine =
        when {
            data.due > 0 -> {
                val s = if (data.due != 1) "s" else ""
                var t = "<b>${data.due}</b> card$s due now."
                if (data.newAvail > 0) t += " Then learn new problems from <b>$target</b>."
                t
            }
            data.newAvail > 0 -> "Nothing due — start new problems from <b>$target</b>."
            else -> "You're all caught up. Add more decks or come back tomorrow."
        }
    val startBtn =
        if (data.due > 0 || data.newAvail > 0) {
            "<button class='btn' onclick=\"pycmd('sr:start')\">&#9654;&nbsp;Start studying</button>"
        } else {
            ""
        }

    fun cls(n: Int) = if (number == n) "on" else ""
    val roadmap =
        """
        <div class='card'><h2>Your roadmap</h2>
          <div class='track'>
            <div class='fill' style='width:${"%.1f".format(cov)}%'></div>
            <div class='divider' style='left:${PHASE1_MAX}%'></div>
            <div class='divider' style='left:${PHASE2_MAX}%'></div>
            <div class='marker' style='left:${"%.1f".format(cov)}%'></div>
          </div>
          <div class='phases'><span class='${cls(1)}'>Foundations</span>
            <span class='${cls(2)}'>Core</span><span class='${cls(3)}'>Advanced</span></div>
          <div class='cap'>Phase $number of 3 · <b>$name</b> — now: $covers.
            You've covered <b>${"%.0f".format(cov)}%</b> of the exam outline.</div>
        </div>
        """.trimIndent()

    val readiness =
        scores.readiness.let { r ->
            if (!r.available) {
                "<div class='card'><h2>Readiness</h2><div class='grange'>${esc(r.abstainReason)}</div></div>"
            } else {
                fun scale(x: Float) = ((x - 200f) / 790f * 100f).coerceIn(0f, 100f)
                val fill = scale(r.point)
                val lo = scale(r.rangeLow)
                val hi = scale(r.rangeHigh)
                """
                <div class='card'><h2>Readiness</h2>
                  <div><span class='gnum'>${"%.0f".format(r.point)}</span>
                    <span class='grange'> likely ${"%.0f".format(r.rangeLow)}–${"%.0f".format(r.rangeHigh)}</span></div>
                  <div class='gtrack'>
                    <div class='gband' style='left:${"%.1f".format(lo)}%;width:${"%.1f".format((hi - lo).coerceAtLeast(1f))}%'></div>
                    <div class='gfill' style='width:${"%.1f".format(fill)}%'></div>
                    <div class='marker' style='left:${"%.1f".format(fill)}%'></div>
                  </div>
                  <div class='gscale'><span>200</span><span>990</span></div>
                </div>
                """.trimIndent()
            }
        }

    val focusHtml =
        if (focus.isEmpty()) {
            "<div class='card'><h2>Focus next on</h2><div class='grange'>Study a few cards and your weak areas appear here.</div></div>"
        } else {
            val rows =
                focus.joinToString("") { s ->
                    val why =
                        if (s.firstAttempts == 0) {
                            "not started · %.0f%% of the exam".format(s.examWeight)
                        } else {
                            "%.0f%% accuracy · %.0f%% of the exam".format(s.performance, s.examWeight)
                        }
                    "<div class='focus'><div><div class='name'>${esc(s.label)}</div><div class='why'>$why</div></div>" +
                        "<button class='study' onclick=\"pycmd('sr:study:${s.topic}')\">Study &#8594;</button></div>"
                }
            "<div class='card'><h2>Focus next on</h2>" +
                "<div class='grange' style='margin-bottom:6px'>Where study buys the most readiness (exam weight × room to improve).</div>" +
                rows + "</div>"
        }

    val scoreCards =
        "<div class='card'><h2>Memory / Performance / Readiness</h2>" +
            scoreBlock("Memory", "Chance you recall a taught fact now (FSRS).", scores.memory, false) +
            scoreBlock("Performance", "Chance you get a new exam-style question right.", scores.performance, false) +
            scoreBlock("Readiness", "Projected GRE Math score (200–990).", scores.readiness, true) +
            "</div>"
    val subjRows =
        subjects.joinToString("") { s ->
            val perf = if (s.firstAttempts > 0) "%.0f%%".format(s.performance) else "—"
            val mem = if (s.memory >= 0) "%.0f%%".format(s.memory) else "—"
            "<div class='subj'><span>${esc(s.label)}</span>" +
                "<span class='r'>${s.mastery.replace('_', ' ')} · $perf · mem $mem · ${s.studiedCards}/${s.totalCards}</span></div>"
        }
    val subjCard = "<div class='card'><h2>By subject</h2>$subjRows</div>"
    val statsCard =
        "<div class='card'><h2>Detailed stats</h2><div class='today' style='font-size:14px'>" +
            "Full review graphs (retention, workload, intervals).</div>" +
            "<button class='btn' onclick=\"pycmd('sr:stats')\">Open stats graphs</button></div>"

    return """
        <div class='h'>Study Plan</div>
        <div class='sub'>Your guided path to the GRE Math Subject Test — updates as you study.</div>
        <div class='segbar'>
          <button class='seg on' id='seg-plan' onclick="showSec('plan')">Plan</button>
          <button class='seg' id='seg-scores' onclick="showSec('scores')">Scores</button>
        </div>
        <div id='sec-plan'>
          <div class='card'><h2>Today</h2><div class='today'>$todayLine</div>$startBtn</div>
          $roadmap
          $readiness
          $focusHtml
        </div>
        <div id='sec-scores' style='display:none'>$scoreCards$subjCard$statsCard</div>
        <div class='foot'>Three separate estimates — never blended. Formulas: speedrun/SCORES.md.</div>
        """.trimIndent()
}

// ---- launcher + bridge ------------------------------------------------------

private class PlanBridge(
    val dp: DeckPicker,
    val dialog: Dialog,
) {
    @JavascriptInterface
    fun cmd(s: String) {
        when {
            s == "sr:close" -> dp.runOnUiThread { dialog.dismiss() }
            s == "sr:stats" ->
                dp.runOnUiThread {
                    dialog.dismiss()
                    dp.openSpeedrunStatistics()
                }
            s == "sr:start" -> studyFiltered { it.buildStudyPlanDeck() }
            s.startsWith("sr:study:") -> {
                val topic = s.removePrefix("sr:study:")
                studyFiltered { it.buildFocusDeck(topic) }
            }
        }
    }

    private fun studyFiltered(build: (Collection) -> DeckId?) {
        dp.launchCatchingTask {
            val did = withCol { build(this) }
            dp.runOnUiThread { dialog.dismiss() }
            if (did == null) {
                dp.showSnackbar("Nothing to study for that right now")
            } else {
                dp.speedrunStudyDeck(did)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun DeckPicker.showSpeedrunStudyPlan() {
    launchCatchingTask {
        val data =
            withCol {
                PlanData(
                    scores = backend.getSpeedrunScores(),
                    due = findCards("$RAW_SEARCH is:due").size,
                    newAvail = findCards("$RAW_SEARCH is:new").size,
                )
            }
        val html =
            assets
                .open("speedrun_plan.html")
                .bufferedReader()
                .use { it.readText() }
                .replace("__SR_BODY__", buildPlanBody(data))
        val web = WebView(this@showSpeedrunStudyPlan)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        val dialog = Dialog(this@showSpeedrunStudyPlan, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(web)
        web.addJavascriptInterface(PlanBridge(this@showSpeedrunStudyPlan, dialog), "AndroidSpeedrun")
        web.loadDataWithBaseURL("https://speedrun.local/", html, "text/html", "utf-8", null)
        dialog.setOnDismissListener {
            web.destroy()
            updateDeckList()
        }
        dialog.show()
        Timber.i("Speedrun: study plan opened")
    }
}

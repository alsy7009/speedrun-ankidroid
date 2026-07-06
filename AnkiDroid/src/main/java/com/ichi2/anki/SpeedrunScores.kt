// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun: the three study scores (Memory / Performance / Readiness).
//
// The numbers come from the shared Rust engine (GetSpeedrunScores RPC), so the
// phone shows exactly what the desktop shows. Each score carries the full
// honesty contract: point estimate, likely range, coverage, confidence,
// reasons — or an explicit abstain state naming what data is still missing.

package com.ichi2.anki

import androidx.appcompat.app.AlertDialog
import anki.stats.SpeedrunScore
import anki.stats.SpeedrunScores
import anki.stats.SpeedrunSubjectStat
import com.ichi2.anki.CollectionManager.withCol
import java.text.DateFormat
import java.util.Date

private fun lever(s: SpeedrunSubjectStat): Float = (s.examWeight / 100f) * (1f - s.performance / 100f)

/** Per-subject diagnostics: the biggest levers + a subject-by-subject readout,
 * mirroring the desktop dashboard's "Focus next on" + "By subject" table. */
private fun subjectBreakdown(scores: SpeedrunScores): String =
    buildString {
        val subjects = scores.subjectsList
        if (subjects.isEmpty()) return@buildString
        val focus = subjects.sortedByDescending { lever(it) }.take(3)
        if (focus.isNotEmpty()) {
            append("\nFocus next on (biggest levers):\n")
            for (s in focus) {
                val why =
                    if (s.firstAttempts == 0) {
                        "not started · %.0f%% of exam".format(s.examWeight)
                    } else {
                        "%.0f%% accuracy · %.0f%% of exam".format(s.performance, s.examWeight)
                    }
                append("• ${s.label} — $why\n")
            }
        }
        append("\nBy subject:\n")
        for (s in subjects) {
            val perf = if (s.firstAttempts > 0) "%.0f%%".format(s.performance) else "—"
            val mem = if (s.memory >= 0) "%.0f%%".format(s.memory) else "—"
            append("• ${s.label}: ${s.mastery.replace('_', ' ')} · perf $perf · mem $mem · seen ${s.studiedCards}/${s.totalCards}\n")
        }
    }

private fun formatScore(
    title: String,
    subtitle: String,
    score: SpeedrunScore,
    scaled: Boolean,
): String =
    buildString {
        append("■ $title\n$subtitle\n")
        if (!score.available) {
            append("No score yet. ${score.abstainReason}\n")
        } else {
            if (scaled) {
                append("%.0f  (likely range %.0f–%.0f)\n".format(score.point, score.rangeLow, score.rangeHigh))
            } else {
                append("%.0f%%  (likely range %.0f%%–%.0f%%)\n".format(score.point, score.rangeLow, score.rangeHigh))
            }
            append("Confidence: ${score.confidence}\n")
            for (reason in score.reasonsList) {
                append("• $reason\n")
            }
        }
    }

/** Show the three scores in a dialog (deck-list ⋮ menu). */
fun DeckPicker.showSpeedrunScores() {
    launchCatchingTask {
        val scores = withCol { backend.getSpeedrunScores() }
        val updated = DateFormat.getDateTimeInstance().format(Date(scores.lastUpdated * 1000))
        val message =
            buildString {
                append(
                    "Exam coverage: %.0f%% of the GRE outline\n".format(scores.coveragePercent),
                )
                append("${scores.gradedReviews} graded reviews · ${scores.mcFirstAttempts} first-try answers\n")
                append("Updated $updated\n\n")
                append(formatScore("Memory", "Chance you recall a taught fact right now (FSRS).", scores.memory, scaled = false))
                append("\n")
                append(formatScore("Performance", "Chance you get a new, exam-style question right.", scores.performance, scaled = false))
                append("\n")
                append(formatScore("Readiness", "Projected GRE Math score (200–990).", scores.readiness, scaled = true))
                append("\nThree separate estimates — never blended.\n")
                append(subjectBreakdown(scores))
            }
        AlertDialog
            .Builder(this@showSpeedrunScores)
            .setTitle("Speedrun scores")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

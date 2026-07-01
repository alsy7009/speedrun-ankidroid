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
import com.ichi2.anki.CollectionManager.withCol
import java.text.DateFormat
import java.util.Date

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
                append("\nThree separate estimates — never blended.")
            }
        AlertDialog
            .Builder(this@showSpeedrunScores)
            .setTitle("Speedrun scores")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

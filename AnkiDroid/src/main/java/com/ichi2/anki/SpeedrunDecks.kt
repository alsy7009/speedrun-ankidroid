// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun: bundled competition decks.
//
// Ships the .apkg decks in the app's assets, auto-imports the default set
// (Mixed + pure AMC 8/10/12 + GRE) on first launch, and keeps an in-app picker
// for re-importing any of them.

package com.ichi2.anki

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import anki.import_export.ImportAnkiPackageOptions
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.compat.CompatHelper
import com.ichi2.anki.snackbar.showSnackbar
import timber.log.Timber
import java.io.File

/** A competition deck bundled in the app's assets. */
data class BundledDeck(
    val code: String,
    val label: String,
    val asset: String,
    val starter: Boolean,
)

object SpeedrunDecks {
    private const val PREF_IMPORTED = "speedrun_imported_deck_codes"
    private const val LEGACY_PREF = "speedrun_tier_decks_imported"
    private const val ASSET_DIR = "speedrun_decks"

    /**
     * Bundled decks. The `Mixed` sets are the primary practice: one per AMC
     * difficulty tier, each a single flat deck where GRE problems are spread
     * evenly through that tier's recent AMC, so GRE topics (calculus, analysis,
     * linear/abstract algebra, ...) are guaranteed to appear in every session and
     * interleave with AMC by topic. They are imported on first launch. The three
     * AMC difficulty tiers (each a folder of full-named subdecks like
     * `AMC 10A 2023`) and the GRE topic deck are dedicated single-source sets,
     * opt-in via the picker (their content also lives inside the Mixed sets).
     */
    val decks: List<BundledDeck> =
        listOf(
            BundledDeck("MIXED_AMC_8", "Mixed: AMC 8 + GRE", "$ASSET_DIR/MIXED_AMC_8.apkg", starter = true),
            BundledDeck("MIXED_AMC_10", "Mixed: AMC 10 + GRE", "$ASSET_DIR/MIXED_AMC_10.apkg", starter = true),
            BundledDeck("MIXED_AMC_12", "Mixed: AMC 12 + GRE", "$ASSET_DIR/MIXED_AMC_12.apkg", starter = true),
            BundledDeck("AMC_8", "AMC 8 only", "$ASSET_DIR/AMC_8.apkg", starter = true),
            BundledDeck("AMC_10", "AMC 10 only", "$ASSET_DIR/AMC_10.apkg", starter = true),
            BundledDeck("AMC_12", "AMC 12 only", "$ASSET_DIR/AMC_12.apkg", starter = true),
            BundledDeck("GRE", "GRE only (calculus, analysis, algebra, ...)", "$ASSET_DIR/GRE.apkg", starter = true),
        )

    private fun copyAssetToCache(
        context: Context,
        asset: String,
    ): String {
        val dest = File(context.cacheDir, asset.substringAfterLast('/'))
        context.assets.open(asset).use { input ->
            CompatHelper.compat.copyFile(input, dest.absolutePath)
        }
        return dest.absolutePath
    }

    private fun importOptions(): ImportAnkiPackageOptions =
        ImportAnkiPackageOptions
            .newBuilder()
            .setMergeNotetypes(true)
            .setWithScheduling(false)
            .setWithDeckConfigs(false)
            .build()

    /** Copy a bundled deck out of assets and import it into the collection. */
    suspend fun importDeck(
        context: Context,
        deck: BundledDeck,
    ) {
        val path = copyAssetToCache(context, deck.asset)
        withCol { importAnkiPackage(path, importOptions()) }
    }

    /** Deck codes already imported. Migrates the old single-boolean marker
     * (which meant the 3 AMC tiers were imported) to the per-code set. */
    fun importedCodes(context: Context): MutableSet<String> {
        val prefs = context.sharedPrefs()
        prefs.getStringSet(PREF_IMPORTED, null)?.let { return it.toMutableSet() }
        return if (prefs.getBoolean(LEGACY_PREF, false)) {
            mutableSetOf("AMC_8", "AMC_10", "AMC_12")
        } else {
            mutableSetOf()
        }
    }

    fun setImportedCodes(
        context: Context,
        codes: Set<String>,
    ) {
        context.sharedPrefs().edit { putStringSet(PREF_IMPORTED, codes) }
    }

    private const val RAW_PARENT = "Speedrun::Raw Card Decks"

    /**
     * Idempotent deck reorganization (mirrors the desktop qt/aqt/speedrun_migrate.py):
     * rename `Speedrun::Recommended` -> `Speedrun::Missed Topics`, and tuck every
     * other raw `Speedrun::<x>` deck under `Speedrun::Raw Card Decks` so the
     * curated Study Plan / Missed Topics decks stand out. Only writes when a
     * deck still needs moving, so it also catches decks imported later. Never
     * gates — the user can still study any deck.
     */
    suspend fun migrateDeckLayout() {
        val curated = setOf("Speedrun::Study Plan", "Speedrun::Missed Topics", RAW_PARENT)
        withCol {
            decks.idForName("Speedrun::Recommended")?.let { rec ->
                if (decks.idForName("Speedrun::Missed Topics") == null) {
                    try {
                        decks.rename(rec, "Speedrun::Missed Topics")
                    } catch (e: Exception) {
                        Timber.w(e, "Speedrun: rename Recommended failed")
                    }
                }
            }
            for (entry in decks.allNamesAndIds(skipEmptyDefault = true, includeFiltered = false)) {
                val name = entry.name
                if (!name.startsWith("Speedrun::")) continue
                val rest = name.removePrefix("Speedrun::")
                if (rest.contains("::")) continue // not a direct child; moves with its parent
                if (name in curated || name == "Speedrun::Recommended") continue
                if (name.startsWith(RAW_PARENT)) continue
                try {
                    decks.rename(entry.id, "$RAW_PARENT::$rest")
                } catch (e: Exception) {
                    Timber.w(e, "Speedrun: move %s failed", name)
                }
            }
        }
    }
}

/** Import any not-yet-imported starter decks on launch (the Mixed sets). New
 * decks import without re-importing/duplicating ones already present. */
fun DeckPicker.maybeImportBundledStarterDecks() {
    val imported = SpeedrunDecks.importedCodes(this)
    val toImport = SpeedrunDecks.decks.filter { it.starter && it.code !in imported }
    launchCatchingTask {
        if (toImport.isNotEmpty()) {
            withProgress {
                for (deck in toImport) {
                    Timber.i("Speedrun: importing bundled deck %s", deck.label)
                    SpeedrunDecks.importDeck(this@maybeImportBundledStarterDecks, deck)
                }
            }
            imported.addAll(toImport.map { it.code })
            SpeedrunDecks.setImportedCodes(this@maybeImportBundledStarterDecks, imported)
        }
        // Reorganize (idempotent): rename Recommended -> Missed Topics and tuck
        // raw content decks under "Speedrun::Raw Card Decks". Runs every launch so
        // it also catches decks imported/synced later.
        SpeedrunDecks.migrateDeckLayout()
        updateDeckList()
        if (toImport.isNotEmpty()) showSnackbar("Added ${toImport.size} Speedrun deck(s)")
    }
}

/** Show a picker to import any of the bundled decks (Mixed sets, AMC tiers, GRE). */
fun DeckPicker.showSpeedrunDeckPicker() {
    val labels = SpeedrunDecks.decks.map { it.label }.toTypedArray()
    AlertDialog
        .Builder(this)
        .setTitle("Import a Speedrun deck")
        .setItems(labels) { _, index ->
            val deck = SpeedrunDecks.decks[index]
            launchCatchingTask {
                withProgress { SpeedrunDecks.importDeck(this@showSpeedrunDeckPicker, deck) }
                updateDeckList()
                showSnackbar("Imported ${deck.label}")
            }
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun: bundled competition decks.
//
// Ships the .apkg decks in the app's assets, auto-imports the Mixed starter sets
// on first launch, and adds an in-app picker to import the others.

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
            BundledDeck("AMC_8", "AMC 8 only", "$ASSET_DIR/AMC_8.apkg", starter = false),
            BundledDeck("AMC_10", "AMC 10 only", "$ASSET_DIR/AMC_10.apkg", starter = false),
            BundledDeck("AMC_12", "AMC 12 only", "$ASSET_DIR/AMC_12.apkg", starter = false),
            BundledDeck("GRE", "GRE only (calculus, analysis, algebra, ...)", "$ASSET_DIR/GRE.apkg", starter = false),
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
}

/** Import any not-yet-imported starter decks on launch (the Mixed sets). New
 * decks import without re-importing/duplicating ones already present. */
fun DeckPicker.maybeImportBundledStarterDecks() {
    val imported = SpeedrunDecks.importedCodes(this)
    val toImport = SpeedrunDecks.decks.filter { it.starter && it.code !in imported }
    if (toImport.isEmpty()) return
    launchCatchingTask {
        withProgress {
            for (deck in toImport) {
                Timber.i("Speedrun: importing bundled deck %s", deck.label)
                SpeedrunDecks.importDeck(this@maybeImportBundledStarterDecks, deck)
            }
        }
        imported.addAll(toImport.map { it.code })
        SpeedrunDecks.setImportedCodes(this@maybeImportBundledStarterDecks, imported)
        updateDeckList()
        showSnackbar("Added ${toImport.size} Speedrun deck(s)")
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

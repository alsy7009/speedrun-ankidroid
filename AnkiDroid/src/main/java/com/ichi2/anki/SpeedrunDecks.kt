// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun: bundled AMC competition decks.
//
// Ships the AMC .apkg decks in the app's assets, auto-imports three starter
// decks on first launch, and adds an in-app picker to import the others.

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
    val label: String,
    val asset: String,
    val starter: Boolean,
)

object SpeedrunDecks {
    private const val PREF_IMPORTED = "speedrun_tier_decks_imported"
    private const val ASSET_DIR = "speedrun_decks"

    /**
     * The three AMC difficulty tiers shipped in assets. Each tier folder holds
     * every contest+year as a full-named subdeck (e.g. `AMC 10A 2023`), with the
     * A/B variants and historical predecessors folded in. All three are imported
     * automatically on first launch; the picker can re-add any of them.
     */
    val decks: List<BundledDeck> =
        listOf(
            BundledDeck("AMC 8", "$ASSET_DIR/AMC_8.apkg", starter = true),
            BundledDeck("AMC 10", "$ASSET_DIR/AMC_10.apkg", starter = true),
            BundledDeck("AMC 12", "$ASSET_DIR/AMC_12.apkg", starter = true),
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

    fun alreadyImported(context: Context): Boolean = context.sharedPrefs().getBoolean(PREF_IMPORTED, false)

    fun markImported(context: Context) {
        context.sharedPrefs().edit { putBoolean(PREF_IMPORTED, true) }
    }
}

/** Import the three starter AMC decks once, on first launch. */
fun DeckPicker.maybeImportBundledStarterDecks() {
    if (SpeedrunDecks.alreadyImported(this)) return
    launchCatchingTask {
        withProgress {
            for (deck in SpeedrunDecks.decks.filter { it.starter }) {
                Timber.i("Speedrun: importing starter deck %s", deck.label)
                SpeedrunDecks.importDeck(this@maybeImportBundledStarterDecks, deck)
            }
        }
        SpeedrunDecks.markImported(this@maybeImportBundledStarterDecks)
        updateDeckList()
        showSnackbar("Added AMC decks (AMC 8, 10, 12)")
    }
}

/** Show a picker to import any of the bundled AMC decks. */
fun DeckPicker.showSpeedrunDeckPicker() {
    val labels = SpeedrunDecks.decks.map { it.label }.toTypedArray()
    AlertDialog
        .Builder(this)
        .setTitle("Import AMC deck")
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

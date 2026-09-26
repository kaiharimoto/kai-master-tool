package com.kaiharimoto.mastertool.core.data

import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import com.kaiharimoto.mastertool.core.deck.SortMode
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.prefs.SectionPreferences
import com.kaiharimoto.mastertool.core.prefs.UiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreferencesRepositoryTest {

    private fun repository(database: MasterToolDatabase) =
        PreferencesRepository(database, Dispatchers.Unconfined)

    @Test
    fun returnsDefaultsWhenNothingHasBeenSaved() = runTest {
        assertEquals(UiPreferences.DEFAULT, repository(testDatabase()).load())
    }

    @Test
    fun roundTripsWhatWasSaved() = runTest {
        val repository = repository(testDatabase())
        val prefs = UiPreferences.DEFAULT
            .copy(searchWeight = 0.45f, stacked = true)
            .with(DeckSection.MAIN, SectionPreferences(2.5f, 12, sortMode = SortMode.TYPE))

        repository.save(prefs)

        assertEquals(prefs, repository.load())
    }

    @Test
    fun savingTwiceReplacesRatherThanAccumulating() = runTest {
        val database = testDatabase()
        val repository = repository(database)

        repository.save(UiPreferences.DEFAULT.copy(searchWeight = 0.3f))
        repository.save(UiPreferences.DEFAULT.copy(searchWeight = 0.5f))

        assertEquals(0.5f, repository.load().searchWeight)
    }

    @Test
    fun aCorruptDocumentFallsBackToDefaults() = runTest {
        // Settings survive updates and half-written files. Failing to read them is
        // never a reason to fail to start.
        val database = testDatabase()
        database.preferenceQueries.upsert(PreferencesRepository.KEY, "{not json at all")

        assertEquals(UiPreferences.DEFAULT, repository(database).load())
    }

    @Test
    fun anUnusableWeightIsRepairedOnTheWayBackIn() = runTest {
        val database = testDatabase()
        database.preferenceQueries.upsert(
            PreferencesRepository.KEY,
            """{"main":{"weight":0.0,"columns":10}}""",
        )

        val loaded = repository(database).load()

        assertTrue(loaded.main.weight >= SectionPreferences.MIN_WEIGHT)
    }

    @Test
    fun anUnusableWeightIsAlsoRepairedOnTheWayOut() = runTest {
        val database = testDatabase()
        val repository = repository(database)

        repository.save(
            UiPreferences.DEFAULT.with(DeckSection.SIDE, SectionPreferences(0f, 10))
        )

        assertTrue(repository.load().side.weight >= SectionPreferences.MIN_WEIGHT)
    }

    @Test
    fun theDesktopDocumentLivesBesideTheTabletOne() = runTest {
        val repository = repository(testDatabase())
        val tablet = UiPreferences.DEFAULT.copy(searchWeight = 0.5f)
        val desk = NeuePreferences.DEFAULT.copy(theme = NeueTheme.INK, scale = 1.5f, poolWidth = 520f)

        repository.save(tablet)
        repository.saveNeue(desk)

        assertEquals(tablet, repository.load())
        assertEquals(desk, repository.loadNeue())
    }

    @Test
    fun theDesktopDocumentDefaultsWhenAbsent() = runTest {
        assertEquals(NeuePreferences.DEFAULT, repository(testDatabase()).loadNeue())
    }
}

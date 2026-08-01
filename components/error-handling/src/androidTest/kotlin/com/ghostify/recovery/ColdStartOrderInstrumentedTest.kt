package com.ghostify.recovery

import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostify.crash.CrashReporter
import com.ghostify.crash.GhostifyCrashHandler
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-160 (device half): cold start renders from the DB before any network call.
 *
 * The ordering guarantee is implemented by [StartupBootstrap] + [RecoveryGate]:
 *   1. The crash handler is installed first, so no early failure escapes unrecorded.
 *   2. Recovery is a fast, local, DB-only transaction (no network).
 *   3. All network-bound work must `await` the [RecoveryGate], which opens only after
 *      recovery completed — so the first frame fed by Room flows is never raced by network.
 *
 * This test proves, on a real Room DB, that network work cannot begin before recovery
 * (i.e. before the DB is repaired and ready to render).
 *
 * DEVICE-ONLY — run via `connectedAndroidTest` on an emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartOrderInstrumentedTest {

    private lateinit var db: RecoveryTestDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RecoveryTestDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun T160_networkWorkWaitsForRecoveryAndDbIsRenderable() = runBlocking {
        // A previous run was killed mid-download; DB holds in-flight state.
        db.openHelper.writableDatabase.insert("songs", null, ContentValues().apply {
            put("id", "s1")
            put("status", "DOWNLOADING")
            put("playlist_id", "p1")
        })
        db.openHelper.writableDatabase.insert("playlists", null, ContentValues().apply {
            put("id", "p1")
            put("status", "DOWNLOADING")
        })

        val recovery = KilledProcessRecovery(db.recoveryDao()) {}
        val gate = RecoveryGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = Collections.synchronizedList(mutableListOf<String>())

        // Network-bound work must park on the gate until recovery finishes.
        val networkJob = scope.launch {
            gate.await()
            events.add("network-started")
        }

        delay(200)
        assertTrue("network work must NOT start before recovery", events.isEmpty())

        // Cold start: handler first, then DB-only recovery, then open the gate.
        val handler = GhostifyCrashHandler(CrashReporter { true }, previous = null)
        StartupBootstrap(handler, recovery, gate, scope).onColdStart()

        delay(500) // allow the launched recovery run to finish
        assertTrue("gate must be open after recovery", gate.isRecovered.value)

        // DB is repaired and renderable before any network call happened.
        assertEquals(SongStatus.PENDING, db.recoveryDao().songsSnapshot().single().status)

        networkJob.join()
        assertEquals(listOf("network-started"), events.toList())
        assertTrue("crash handler must be installed first", Thread.getDefaultUncaughtExceptionHandler() === handler)
    }
}

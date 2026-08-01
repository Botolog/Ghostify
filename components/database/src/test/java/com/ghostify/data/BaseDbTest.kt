package com.ghostify.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ghostify.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Base for JVM (Robolectric) Room tests: an in-memory, real-SQLite database built at
 * the latest schema version so every query, FK and index behaves exactly as on-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseDbTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected lateinit var context: Context
    protected lateinit var db: AppDatabase

    @Before
    open fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = buildDatabase(context)
    }

    protected open fun buildDatabase(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*com.ghostify.data.db.Migrations.ALL)
            .build()

    @After
    fun tearDown() {
        db.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description?) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description?) {
        Dispatchers.resetMain()
    }
}

package xyz.botolog.ghostify.widgetlab

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.botolog.ghostify.ui.player.LrcLine
import xyz.botolog.ghostify.ui.player.currentLineIndex
import xyz.botolog.ghostify.ui.player.parseLrc
import xyz.botolog.ghostify.data.model.LibraryStats
import xyz.botolog.ghostify.sync.SyncStatus
import xyz.botolog.ghostify.sync.SyncStatusHolder
import xyz.botolog.ghostify.widget.LibraryStatsWidget
import xyz.botolog.ghostify.widget.LibraryStatsWidgetReceiver
import xyz.botolog.ghostify.widget.LyricsWidget
import xyz.botolog.ghostify.widget.LyricsWidgetReceiver
import xyz.botolog.ghostify.widget.NextUpItem
import xyz.botolog.ghostify.widget.NextUpWidget
import xyz.botolog.ghostify.widget.NextUpWidgetReceiver
import xyz.botolog.ghostify.widget.PlayerWidget
import xyz.botolog.ghostify.widget.PlayerWidgetReceiver
import xyz.botolog.ghostify.widget.PlaylistLauncherWidget
import xyz.botolog.ghostify.widget.PlaylistLauncherWidgetReceiver
import xyz.botolog.ghostify.widget.PlaylistTile
import xyz.botolog.ghostify.widget.ShuffleRouletteWidget
import xyz.botolog.ghostify.widget.ShuffleRouletteWidgetReceiver
import xyz.botolog.ghostify.widget.SleepTimerWidget
import xyz.botolog.ghostify.widget.SleepTimerWidgetReceiver
import xyz.botolog.ghostify.widget.WidgetSnapshot
import xyz.botolog.ghostify.widget.WidgetStateProvider
import xyz.botolog.ghostify.widget.lyricsWindow
import java.io.ByteArrayOutputStream

/**
 * DEBUG-ONLY widget lab (src/debug): renders the real Ghostify Glance widgets with
 * synthetic [WidgetSnapshot]s — no music, no playback, no downloads.
 *
 * Hosts all seven widgets: **player** (4x2), **lyrics** (4x4), **sleep** (2x2),
 * **nextup** (4x3), **roulette** (4x2), **playlists** (4x2) and **library** (4x2). Route: in-process `AppWidgetHost`. The activity
 * allocates its own app-widget ids, binds them to the real receivers, and inflates the
 * composed RemoteViews into `AppWidgetHostView`s sized to the requested cell.
 *
 * **Arbitrary sizes.** A scenario can pin the widget to any dp size:
 * ```
 *   adb shell am start -n xyz.botolog.ghostify/.widgetlab.WidgetLabActivity \
 *       --es scenario sleep_active_mid --es which sleep --es size 4x3
 *   adb shell am start ... --es scenario nextup_many --es which nextup --es size 4x3
 *   adb shell am start ... --es scenario roulette_shuffle_on --es which roulette --es size 4x2
 *   adb shell am start ... --es scenario player_playing --es which both --es size 6x2
 *   adb shell am start ... --es scenario playlists_four --es which playlists --es size 4x2
 *   adb shell am start ... --es scenario library_idle --es which library --es size 4x3
 * ```
 * Extras: `w`/`h` (dp), `size` (`WxH` **cells**, e.g. `4x2`), `which`
 * (`player` | `lyrics` | `sleep` | `nextup` | `roulette` | `playlists` | `library` | `both` | `bothb` | `all`, default `both`),
 * `scenario`, `reset`.
 *
 * State injection: because `src/debug` shares the module + package root, this class can
 * call the widget internals directly — [WidgetStateProvider.publish] to install a
 * synthetic snapshot, plus [parseLrc] / [currentLineIndex] / [lyricsWindow] to build
 * realistic timed lyrics, and [WidgetStateProvider.publishPlaylists] /
 * [WidgetStateProvider.publishStats] + `SyncStatusHolder` for the library widgets. The live
 * composition observes those flows, so publishing recomposes the widget; we also call
 * `updateAll` to (re)start sessions.
 *
 * Scenarios: player_playing | player_paused | player_empty | player_huge_cover |
 *            lyrics_synced_mid | lyrics_synced_end | lyrics_plain | lyrics_none | lyrics_real_lrc |
 *            sleep_active_mid | sleep_active_almost_done | sleep_inactive |
 *            nextup_many | nextup_one | nextup_empty |
 *            roulette_shuffle_on | roulette_shuffle_off |
 *            playlists_four | playlists_one | playlists_none |
 *            library_idle | library_syncing | library_just_now | library_never | library_large
 */
class WidgetLabActivity : Activity() {

    private lateinit var host: AppWidgetHost
    private lateinit var mgr: AppWidgetManager
    private lateinit var statusView: TextView
    private lateinit var scenarioView: TextView

    /** One container per bound slot, in slot order. */
    private val containers = LinkedHashMap<Int, FrameLayout>()
    private val widgetIds = LinkedHashMap<Int, Int>()

    private var pendingSlot = SLOT_NONE
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var viewsReady = false
    private var binding = false
    private var currentScenario = DEFAULT_SCENARIO

    // Target size in dp, and which widget(s) to show.
    private var targetW = DEFAULT_WIDTH_DP
    private var targetH = DEFAULT_HEIGHT_DP
    private var which = WHICH_BOTH

    private var heartbeat: kotlinx.coroutines.Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, HOST_ID)
        mgr = AppWidgetManager.getInstance(this)
        parseSize(intent)
        buildUi()
        Log.i(
            TAG,
            "onCreate scenario=${scenarioOf(intent)} which=$which size=${targetW}x${targetH}dp " +
                "reset=${intent?.getBooleanExtra(EXTRA_RESET, false)}",
        )
    }

    override fun onStart() {
        super.onStart()
        try {
            host.startListening()
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
        }
        maybeStartBinding()
        if (viewsReady) startHeartbeat()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseSize(intent)
        Log.i(TAG, "onNewIntent scenario=${scenarioOf(intent)} which=$which size=${targetW}x${targetH}dp")
        if (viewsReady) {
            // Rebuild the chrome for the new size, then re-attach the existing bound host
            // views at the new dimensions.
            buildUi()
            onAllBound()
        } else {
            maybeStartBinding()
        }
    }

    override fun onStop() {
        super.onStop()
        heartbeat?.cancel()
        try {
            host.stopListening()
        } catch (_: Throwable) {
        }
    }

    // Bound ids are intentionally NOT deleted on destroy: the lab reuses them so every
    // launch is dialog-free. Use `--ez reset true` to drop them.

    // ── Size plumbing ─────────────────────────────────────────────────────

    /** Reads `w`/`h` (dp), `size` (WxH cells) and `which` from the launch intent. */
    private fun parseSize(intent: Intent?) {
        targetW = DEFAULT_WIDTH_DP
        targetH = DEFAULT_HEIGHT_DP
        which = WHICH_BOTH
        if (intent == null) return
        val spec = intent.getStringExtra(EXTRA_SIZE)
        if (!spec.isNullOrBlank()) {
            cellsToDp(spec)?.let { (w, h) ->
                targetW = w
                targetH = h
            }
        }
        if (intent.hasExtra(EXTRA_W)) targetW = intent.getIntExtra(EXTRA_W, targetW)
        if (intent.hasExtra(EXTRA_H)) targetH = intent.getIntExtra(EXTRA_H, targetH)
        targetW = targetW.coerceIn(40, 2000)
        targetH = targetH.coerceIn(30, 2000)
        which = intent.getStringExtra(EXTRA_WHICH)?.lowercase()?.let {
            if (it in KNOWN_WHICH) it else WHICH_BOTH
        } ?: WHICH_BOTH
    }

    /** The slots the current `which` needs (in order). */
    private fun requiredSlots(): List<Int> = when (which) {
        WHICH_PLAYER -> listOf(SLOT_PLAYER)
        WHICH_LYRICS -> listOf(SLOT_LYRICS)
        WHICH_SLEEP -> listOf(SLOT_SLEEP)
        WHICH_NEXTUP -> listOf(SLOT_NEXTUP)
        WHICH_ROULETTE -> listOf(SLOT_ROULETTE)
        WHICH_PLAYLISTS -> listOf(SLOT_PLAYLISTS)
        WHICH_LIBRARY -> listOf(SLOT_LIBRARY)
        WHICH_BOTHB -> listOf(SLOT_PLAYLISTS, SLOT_LIBRARY)
        WHICH_ALL -> ALL_SLOTS
        else -> listOf(SLOT_PLAYER, SLOT_LYRICS)
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#FF7A7A7A"))
        }

        scenarioView = label("WidgetLab · $which · ${targetW}x${targetH}dp", 15f, Color.BLACK).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        statusView = label("binding…", 11f, Color.parseColor("#FF101010"))
        root.addView(scenarioView)
        root.addView(statusView)
        root.addView(space(6))

        containers.clear()
        for (slot in requiredSlots()) {
            root.addView(label("${slotTitle(slot)} widget — ${targetW}x${targetH}dp", 12f, Color.BLACK))
            val container = FrameLayout(this)
            containers[slot] = container
            root.addView(container, LinearLayout.LayoutParams(dp(targetW), dp(targetH)))
            root.addView(space(10))
        }

        setContentView(
            ScrollView(this).apply {
                // Same neutral grey as the root so a screenshot tool can detect the widget
                // rectangle as "the non-grey region" without the empty area below the
                // content confusing it.
                setBackgroundColor(Color.parseColor("#FF7A7A7A"))
                addView(root)
            },
        )
    }

    private fun label(text: String, sizeSp: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            gravity = Gravity.START
        }

    private fun space(dp: Int): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            this@WidgetLabActivity.dp(dp),
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── AppWidgetHost binding (sequential, one dialog at a time) ───────────

    private fun maybeStartBinding() {
        if (viewsReady || binding || pendingSlot != SLOT_NONE) return
        binding = true
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (intent?.getBooleanExtra(EXTRA_RESET, false) == true) {
            for (slot in ALL_SLOTS) {
                val stored = prefs.getInt(keyFor(slot), AppWidgetManager.INVALID_APPWIDGET_ID)
                if (stored != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    runCatching { host.deleteAppWidgetId(stored) }
                }
                prefs.edit().remove(keyFor(slot)).apply()
            }
        }
        widgetIds.clear()
        for (slot in requiredSlots()) {
            val id = validStoredId(prefs, keyFor(slot))
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) widgetIds[slot] = id
        }
        Log.i(TAG, "stored ids ${widgetIds.entries.joinToString { "${slotName(it.key)}=${it.value}" }}")
        val missing = requiredSlots().firstOrNull { !widgetIds.containsKey(it) }
        if (missing != null) bindSlot(missing) else onAllBound()
    }

    private fun validStoredId(prefs: android.content.SharedPreferences, key: String): Int {
        val id = prefs.getInt(key, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return id
        return if (mgr.getAppWidgetInfo(id) != null) id else AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun bindSlot(slot: Int) {
        val id = host.allocateAppWidgetId()
        val cn = componentFor(slot)
        val allowed = mgr.bindAppWidgetIdIfAllowed(id, cn)
        Log.i(TAG, "bind slot=${slotName(slot)} id=$id allowed=$allowed")
        if (allowed) {
            onSlotBound(slot, id)
        } else {
            pendingSlot = slot
            pendingId = id
            statusView.text = "waiting for system bind consent (tick “Always allow” then Create)…"
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, cn),
                REQ_BIND,
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BIND) return
        val slot = pendingSlot
        val id = pendingId
        pendingSlot = SLOT_NONE
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (resultCode == RESULT_OK && slot != SLOT_NONE) {
            Log.i(TAG, "bind consented slot=${slotName(slot)} id=$id")
            onSlotBound(slot, id)
        } else {
            Log.e(TAG, "bind denied slot=${slotName(slot)} resultCode=$resultCode")
            statusView.text = "bind denied for slot=${slotName(slot)} (resultCode=$resultCode)"
        }
    }

    private fun onSlotBound(slot: Int, id: Int) {
        widgetIds[slot] = id
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(keyFor(slot), id).apply()
        val missing = requiredSlots().firstOrNull { !widgetIds.containsKey(it) }
        if (missing != null) bindSlot(missing) else onAllBound()
    }

    private fun onAllBound() {
        binding = false
        for (slot in requiredSlots()) {
            val id = widgetIds[slot] ?: AppWidgetManager.INVALID_APPWIDGET_ID
            val container = containers[slot] ?: continue
            container.removeAllViews()
            val info = mgr.getAppWidgetInfo(id)
            if (info == null) {
                Log.e(TAG, "missing provider info for ${slotName(slot)} id=$id")
                statusView.text = "missing provider info for ${slotName(slot)} (id=$id)"
                continue
            }
            container.addView(createHostView(id, info), match())
        }
        viewsReady = true
        Log.i(TAG, "views ready ids=${widgetIds.entries.joinToString { "${slotName(it.key)}=${it.value}" }} size=${targetW}x${targetH}dp")
        applyScenario(intent)
        // Glance reads its `LocalSize` from the options, not from the host view's bounds. A
        // real launcher reports the widget's *content* area (its own gutter removed), so we
        // wait for layout and then write the measured content area — otherwise the lab would
        // compose for a size that still includes the host's padding and every widget would
        // look ~16dp too big (the clipping seen in the v3 matrix).
        // Post on the always-attached scenario label: a container that is not part of the
        // view tree never runs its post runnables.
        scenarioView.post {
            for ((slot, container) in containers) {
                applyContentSizeOptions(widgetIds[slot] ?: AppWidgetManager.INVALID_APPWIDGET_ID, container)
            }
            logHostGeometry()
            logComposedSizes()
        }
    }

    /**
     * Writes the measured **content area** of the bound host view (its real width/height
     * minus the host's own padding) into the widget's AppWidget options so Glance's
     * `SizeMode.Exact` composition sees the same size the content is actually laid out at.
     */
    private fun applyContentSizeOptions(id: Int, container: FrameLayout) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val hostView = container.getChildAt(0) ?: return
        val density = resources.displayMetrics.density
        val wDp = ((hostView.width - hostView.paddingLeft - hostView.paddingRight) / density)
            .toInt().coerceAtLeast(1)
        val hDp = ((hostView.height - hostView.paddingTop - hostView.paddingBottom) / density)
            .toInt().coerceAtLeast(1)
        val bundle = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        runCatching { mgr.updateAppWidgetOptions(id, bundle) }
            .onFailure { Log.e(TAG, "updateAppWidgetOptions($id) failed", it) }
        Log.i(TAG, "options id=$id content=${wDp}x${hDp}dp")
    }

    /** Logs the laid-out host view + its RemoteViews root, for size debugging from logcat. */
    private fun logHostGeometry() {
        for ((slot, container) in containers) {
            val v = container.getChildAt(0) ?: continue
            val root = (v as? ViewGroup)?.getChildAt(0)
            Log.i(
                TAG,
                "geometry ${slotName(slot)} view=${v.width}x${v.height}px " +
                    "pad=${v.paddingLeft},${v.paddingTop},${v.paddingRight},${v.paddingBottom} " +
                    "root=${root?.let { "${it.width}x${it.height}" }}",
            )
        }
    }

    /** Asks Glance which sizes it will compose the bound widgets at, and logs them. */
    private fun logComposedSizes() {
        scope.launch {
            val manager = GlanceAppWidgetManager(this@WidgetLabActivity)
            for ((slot, id) in widgetIds) {
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) continue
                runCatching {
                    val glanceId = manager.getGlanceIdBy(id)
                    val sizes = manager.getAppWidgetSizes(glanceId)
                    Log.i(
                        TAG,
                        "glance ${slotName(slot)} sizes=" +
                            sizes.joinToString(",") { "${it.width.value.toInt()}x${it.height.value.toInt()}" },
                    )
                }.onFailure { Log.e(TAG, "getAppWidgetSizes(${slotName(slot)}) failed", it) }
            }
        }
    }

    private fun createHostView(id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        try {
            host.createView(this, id, info)
        } catch (t: Throwable) {
            Log.e(TAG, "createView failed for $id", t)
            AppWidgetHostView(this).apply { addView(label("createView failed: ${t.message}", 10f, Color.RED)) }
        }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun componentFor(slot: Int): ComponentName = when (slot) {
        SLOT_PLAYER -> ComponentName(this, PlayerWidgetReceiver::class.java)
        SLOT_LYRICS -> ComponentName(this, LyricsWidgetReceiver::class.java)
        SLOT_SLEEP -> ComponentName(this, SleepTimerWidgetReceiver::class.java)
        SLOT_NEXTUP -> ComponentName(this, NextUpWidgetReceiver::class.java)
        SLOT_ROULETTE -> ComponentName(this, ShuffleRouletteWidgetReceiver::class.java)
        SLOT_PLAYLISTS -> ComponentName(this, PlaylistLauncherWidgetReceiver::class.java)
        else -> ComponentName(this, LibraryStatsWidgetReceiver::class.java)
    }

    private fun slotTitle(slot: Int): String = when (slot) {
        SLOT_PLAYER -> "Player"
        SLOT_LYRICS -> "Lyrics"
        SLOT_SLEEP -> "Sleep timer"
        SLOT_NEXTUP -> "Next up"
        SLOT_ROULETTE -> "Shuffle Roulette"
        SLOT_PLAYLISTS -> "Playlists"
        else -> "Library"
    }

    // ── Scenario plumbing ──────────────────────────────────────────────────

    private fun scenarioOf(intent: Intent?): String =
        intent?.getStringExtra(EXTRA_SCENARIO)?.takeIf { it.isNotBlank() } ?: DEFAULT_SCENARIO

    private fun applyScenario(intent: Intent?) {
        currentScenario = scenarioOf(intent)
        val snapshot = LabScenarios.snapshot(currentScenario)
        WidgetStateProvider.publish(snapshot)
        publishLibraryState(currentScenario)

        val window = lyricsWindow(snapshot.lyricsLines, snapshot.currentLineIndex).map { it.text }
        val intended = snapshot.currentLineIndex
        scenarioView.text = "WidgetLab · $which · ${targetW}x${targetH}dp · $currentScenario"
        statusView.text = buildString {
            append("route=AppWidgetHost  empty=${snapshot.isEmpty}  ")
            append("playing=${snapshot.isPlaying}  pos=${snapshot.positionMs}ms/${snapshot.durationMs}ms  ")
            append("lines=${snapshot.lyricsLines.size}  current=$intended  ")
            append("sleep=${snapshot.sleepTimerActive}/${snapshot.sleepTimerRemainingMs}ms  ")
            append("nextUp=${snapshot.nextUp.size} of ${snapshot.queueCount}  shuffle=${snapshot.shuffleEnabled}")
        }
        Log.i(
            TAG,
            "scenario=$currentScenario size=${targetW}x${targetH}dp published: empty=${snapshot.isEmpty} " +
                "playing=${snapshot.isPlaying} lines=${snapshot.lyricsLines.size} current=$intended " +
                "sleep=${snapshot.sleepTimerActive} nextUp=${snapshot.nextUp.size} shuffle=${snapshot.shuffleEnabled} " +
                "playlists=${LabScenarios.playlistTiles(currentScenario).size} " +
                "stats=${LabScenarios.libraryStats(currentScenario)} " +
                "sync=${LabScenarios.syncStatus(currentScenario, System.currentTimeMillis())} " +
                "window=${window.joinToString(" | ")}",
        )
        startHeartbeat()
    }

    /**
     * Republish a *fresh* snapshot (new cover bytes => a new value, so the `StateFlow`
     * actually emits and the live Glance composition recomposes) and `updateAll` once a
     * second for a few seconds: a widget session (re)started from the cold path before this
     * activity publishes must converge on the synthetic snapshot within ~1s.
     */
    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                val snap = withContext(Dispatchers.Default) { LabScenarios.snapshot(currentScenario) }
                WidgetStateProvider.publish(snap)
                publishLibraryState(currentScenario)
                repaint()
                delay(1_000)
            }
        }
    }

    private suspend fun repaint() {
        runCatching {
            PlayerWidget().updateAll(applicationContext)
            LyricsWidget().updateAll(applicationContext)
            SleepTimerWidget().updateAll(applicationContext)
            NextUpWidget().updateAll(applicationContext)
            ShuffleRouletteWidget().updateAll(applicationContext)
            PlaylistLauncherWidget().updateAll(applicationContext)
            LibraryStatsWidget().updateAll(applicationContext)
        }.onFailure { Log.e(TAG, "updateAll failed", it) }
    }

    /**
     * Publishes the "Playlists" tiles, the "Library" aggregates and the process-wide sync
     * status for [scenario]. The sync holder is a global counter, so it is **reset** before
     * every application (a naive `beginSync()` per heartbeat would grow the count forever).
     */
    private fun publishLibraryState(scenario: String) {
        val now = System.currentTimeMillis()
        WidgetStateProvider.publishPlaylists(LabScenarios.playlistTiles(scenario))
        WidgetStateProvider.publishStats(LabScenarios.libraryStats(scenario))

        val sync = LabScenarios.syncStatus(scenario, now)
        SyncStatusHolder.reset()
        SyncStatusHolder.restore(sync.lastSyncedAtMs)
        if (sync.isSyncing) SyncStatusHolder.beginSync()
        WidgetStateProvider.publishLastSyncedAt(sync.lastSyncedAtMs)
    }

    private companion object {
        const val TAG = "WidgetLab"
        const val HOST_ID = 0x6C61
        const val PREFS = "widgetlab"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_RESET = "reset"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_SIZE = "size"
        const val EXTRA_WHICH = "which"
        const val DEFAULT_SCENARIO = "player_playing"
        const val WHICH_PLAYER = "player"
        const val WHICH_LYRICS = "lyrics"
        const val WHICH_SLEEP = "sleep"
        const val WHICH_NEXTUP = "nextup"
        const val WHICH_ROULETTE = "roulette"
        const val WHICH_PLAYLISTS = "playlists"
        const val WHICH_LIBRARY = "library"
        const val WHICH_BOTH = "both"
        const val WHICH_BOTHB = "bothb"
        const val WHICH_ALL = "all"
        val KNOWN_WHICH = setOf(
            WHICH_PLAYER, WHICH_LYRICS, WHICH_SLEEP, WHICH_NEXTUP, WHICH_ROULETTE,
            WHICH_PLAYLISTS, WHICH_LIBRARY, WHICH_BOTH, WHICH_BOTHB, WHICH_ALL,
        )
        const val DEFAULT_WIDTH_DP = 250
        const val DEFAULT_HEIGHT_DP = 110
        const val REQ_BIND = 0x4242
        const val SLOT_NONE = 0
        const val SLOT_PLAYER = 1
        const val SLOT_LYRICS = 2
        const val SLOT_SLEEP = 3
        const val SLOT_NEXTUP = 4
        const val SLOT_ROULETTE = 5
        const val SLOT_PLAYLISTS = 6
        const val SLOT_LIBRARY = 7
        val ALL_SLOTS = listOf(
            SLOT_PLAYER, SLOT_LYRICS, SLOT_SLEEP, SLOT_NEXTUP, SLOT_ROULETTE,
            SLOT_PLAYLISTS, SLOT_LIBRARY,
        )
        fun slotName(slot: Int): String = when (slot) {
            SLOT_PLAYER -> "player"
            SLOT_LYRICS -> "lyrics"
            SLOT_SLEEP -> "sleep"
            SLOT_NEXTUP -> "nextup"
            SLOT_ROULETTE -> "roulette"
            SLOT_PLAYLISTS -> "playlists"
            SLOT_LIBRARY -> "library"
            else -> "slot$slot"
        }
        fun keyFor(slot: Int): String = "widget_id_${slotName(slot)}"
    }
}

/**
 * Maps a `WxH` cell spec (e.g. `4x2`) to the dp size WayDroid/launchers use for that cell
 * count on the emulator's density. Only the counts the size matrix needs are pinned;
 * anything else can be passed as explicit `w`/`h` dp extras.
 */
internal fun cellsToDp(spec: String): Pair<Int, Int>? {
    val parts = spec.lowercase().split("x").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size != 2) return null
    val (cols, rows) = parts
    if (cols < 1 || rows < 1) return null
    return cellsToDpWidth(cols) to cellsToDpHeight(rows)
}

/** Width in dp for [cols] launcher columns (matches the lab's measured cell geometry). */
internal fun cellsToDpWidth(cols: Int): Int = when {
    cols <= 2 -> 110
    cols == 3 -> 180
    cols == 4 -> 250
    cols == 5 -> 320
    else -> 250 + 70 * (cols - 4)
}

/** Height in dp for [rows] launcher rows. */
internal fun cellsToDpHeight(rows: Int): Int = when {
    rows <= 1 -> 60
    else -> 110 + 70 * (rows - 2)
}

/**
 * Synthetic snapshot fixtures. Pure: no Android widgets, no I/O beyond an in-memory
 * gradient cover. Timed lyrics go through the app's real [parseLrc] so the data path is
 * the same one the live widget uses.
 */
object LabScenarios {

    private const val TITLE = "Midnight Vessel"
    private const val ARTIST = "Neon Tides"
    private const val ALBUM = "Static Bloom"
    private const val SONG_ID = "lab-song-1"
    private const val INTERVAL_MS = 2_000L
    private const val DURATION_MS = 85_000L

    /** Upcoming-queue fixture shared by the "next up" scenarios. */
    private val QUEUE_FIXTURE = listOf(
        "Static Bloom" to "Neon Tides",
        "Glass Harbor" to "Aurora Lane",
        "Paper Lanterns" to "Moth & Flame",
        "Slow Tide" to "Kite Weather",
        "Afterglow" to "Neon Tides",
        "Signal Fade" to "Aurora Lane",
    )

    fun snapshot(scenario: String): WidgetSnapshot {
        val synced: List<LrcLine> = parseLrc(buildLrc())
        val texts = synced.map { it.text }
        fun idxAt(ms: Long) = currentLineIndex(synced, ms)
        fun nextUp(count: Int, from: Int = 3) = QUEUE_FIXTURE.take(count)
            .mapIndexed { i, (t, a) -> NextUpItem(t, a, 200_000L + i * 12_000L, from + i) }

        return when (scenario) {
            "player_empty" -> WidgetSnapshot.EMPTY

            "player_paused" -> base(
                isPlaying = false, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
            )

            // Regression: a real song ships a full-resolution cover (~1368x1368). Decoded
            // unbounded it blows the system's RemoteViews bitmap cap and Glance paints its
            // "Can't show content" error layout. The fix bounds it before it reaches Glance.
            "player_huge_cover" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                cover = hugeCoverBytes,
            )

            // Regression: real stored lyrics are long (hundreds of lines) and some lines are
            // very long. Parsed through the app's real parseLrc.
            "lyrics_real_lrc" -> base(
                isPlaying = true, positionMs = 144_000L,
                lyrics = realLyricTexts(), current = 96,
                cover = hugeCoverBytes,
            )

            "lyrics_synced_mid" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = 20,
            )

            "lyrics_synced_end" -> base(
                isPlaying = true, positionMs = 75_500L,
                lyrics = texts, current = 37,
            )

            "lyrics_plain" -> base(
                isPlaying = true, positionMs = 30_000L,
                lyrics = plainFixtures(), current = -1,
            )

            "lyrics_none" -> base(
                isPlaying = true, positionMs = 30_000L,
                lyrics = emptyList(), current = -1,
            )

            // ── Sleep timer ──────────────────────────────────────────────
            "sleep_inactive" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                sleepActive = false,
            )

            "sleep_active_mid" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                sleepActive = true, sleepRemaining = 9L * 60_000L + 2_000L, sleepTotal = 15L * 60_000L,
            )

            "sleep_active_almost_done" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                sleepActive = true, sleepRemaining = 21_000L, sleepTotal = 15L * 60_000L,
            )

            // ── Next up / queue peek ─────────────────────────────────────
            "nextup_many" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                nextUp = nextUp(6, from = 3), queueCount = 12, queueIndex = 2,
            )

            "nextup_one" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                nextUp = nextUp(1, from = 3), queueCount = 4, queueIndex = 2,
            )

            "nextup_empty" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                nextUp = emptyList(), queueCount = 0, queueIndex = -1,
            )

            // ── Shuffle roulette ─────────────────────────────────────────
            "roulette_shuffle_on" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                shuffle = true, nextUp = nextUp(4), queueCount = 12, queueIndex = 2,
            )

            "roulette_shuffle_off" -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
                shuffle = false, nextUp = nextUp(4), queueCount = 12, queueIndex = 2,
            )

            else -> base(
                isPlaying = true, positionMs = 40_500L,
                lyrics = texts, current = idxAt(40_500L),
            )
        }
    }

    // ── Playlists / Library fixtures ──────────────────────────────────

    private val PLAYLIST_FIXTURE = listOf(
        "Night Drive" to 128,
        "Focus Flow" to 42,
        "Sunday Morning" to 87,
        "Late Night Coding Beats" to 260,
    )

    /** Playlist tiles for the "Playlists" widget; `playlists_*` scenarios vary the count. */
    fun playlistTiles(scenario: String): List<PlaylistTile> {
        val count = when (scenario) {
            "playlists_none" -> 0
            "playlists_one" -> 1
            "playlists_two" -> 2
            "playlists_three" -> 3
            else -> PLAYLIST_FIXTURE.size
        }
        return (0 until count).map { index ->
            val (name, tracks) = PLAYLIST_FIXTURE[index]
            PlaylistTile(
                id = "lab-playlist-$index",
                name = name,
                trackCount = tracks,
                coverBytes = synthTileCover(index),
            )
        }
    }

    /** Library aggregates for the "Library" widget; `library_*` scenarios vary the figures. */
    fun libraryStats(scenario: String): LibraryStats = when (scenario) {
        "library_never" -> LibraryStats.EMPTY
        "library_large" -> LibraryStats(
            totalSongs = 12_345,
            downloadedSongs = 9_876,
            songsWithLyrics = 5_432,
            playlists = 128,
        )
        else -> LibraryStats(
            totalSongs = 1_284,
            downloadedSongs = 980,
            songsWithLyrics = 742,
            playlists = 12,
        )
    }

    /** Global sync status for the "Library" widget; `library_*` scenarios vary the state. */
    fun syncStatus(scenario: String, nowMs: Long): SyncStatus = when (scenario) {
        "library_syncing" -> SyncStatus(isSyncing = true, lastSyncedAtMs = nowMs - 6L * 60_000L)
        "library_never" -> SyncStatus(isSyncing = false, lastSyncedAtMs = null)
        "library_just_now" -> SyncStatus(isSyncing = false, lastSyncedAtMs = nowMs - 5_000L)
        else -> SyncStatus(isSyncing = false, lastSyncedAtMs = nowMs - 3L * 60_000L)
    }

    /** A 64x64 per-playlist cover: a two-colour gradient, encoded as small JPEG bytes. */
    private fun synthTileCover(seed: Int): ByteArray {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val palette = intArrayOf(
            0xFF7C4DFF.toInt(),
            0xFF00BFA5.toInt(),
            0xFFFF6E40.toInt(),
            0xFF2979FF.toInt(),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(palette[seed % palette.size], palette[(seed + 1) % palette.size]),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun base(
        isPlaying: Boolean,
        positionMs: Long,
        lyrics: List<String>,
        current: Int,
        sleepActive: Boolean = false,
        sleepRemaining: Long = 0L,
        sleepTotal: Long = 0L,
        shuffle: Boolean = false,
        nextUp: List<NextUpItem> = emptyList(),
        queueCount: Int = 0,
        queueIndex: Int = -1,
        cover: ByteArray = synthCover(),
    ) = WidgetSnapshot(
        songId = SONG_ID,
        title = TITLE,
        artist = ARTIST,
        album = ALBUM,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = DURATION_MS,
        hasQueue = queueCount > 0,
        coverBytes = cover,
        lyricsLines = lyrics,
        currentLineIndex = current,
        sleepTimerActive = sleepActive,
        sleepTimerRemainingMs = sleepRemaining,
        sleepTimerTotalMs = sleepTotal,
        nextUp = nextUp,
        queueCount = queueCount,
        currentQueueIndex = queueIndex,
        shuffleEnabled = shuffle,
    )

    /** 40 timed lines, one per 2s, formatted exactly like stored LRC. */
    private fun buildLrc(): String {
        val pool = lyricPool
        return (0 until pool.size).joinToString("\n") { i ->
            val ms = i * INTERVAL_MS
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1_000
            val cs = (ms % 1_000) / 10
            "[%02d:%02d.%02d] %s".format(min, sec, cs, pool[i])
        }
    }

    private fun plainFixtures(): List<String> = lyricPool.take(14)

    /**
     * A full-resolution cover, like a real song's embedded artwork (~1368x1368). Cached so
     * the once-a-second heartbeat does not re-encode a multi-megabyte JPEG every tick.
     */
    private val hugeCoverBytes: ByteArray by lazy { encodeCover(1368) }

    /**
     * A long, real-world-sized LRC (200 timestamped lines, every 17th ~400 chars) decoded
     * through the app's real [parseLrc], to pin the "many/long lines" path.
     */
    private fun realLyricTexts(): List<String> {
        val raw = buildString {
            for (i in 0 until 200) {
                val ms = i * 1_500L
                val min = ms / 60_000
                val sec = (ms % 60_000) / 1_000
                val cs = (ms % 1_000) / 10
                val text = if (i % 17 == 0) longLyricLine else "Line $i — ${lyricPool[i % lyricPool.size]}"
                append("[%02d:%02d.%02d] %s".format(min, sec, cs, text))
                if (i < 199) append('\n')
            }
        }
        return parseLrc(raw).map { it.text }
    }

    private val longLyricLine: String =
        "And the static keeps on blooming through the wire while the midnight vessel " +
            "drifts across a sea of carrier waves, every echo of a chorus I have never " +
            "learned to sing, fading into silence, fading into noise, fading into you"

    private val lyricPool = listOf(
        "City lights are bleeding through the rain",
        "I trace the wire, I feel the static",
        "Somewhere a signal calls my name",
        "And every second feels cinematic",
        "Hold the line, hold the line",
        "The night is a river, I'm drifting",
        "Your voice is a ghost in the engine",
        "I keep the frequency shifting",
        "Static bloom, static bloom",
        "Petals of noise on the radio",
        "I hear you breathing in the tune",
        "Every word a faint halo",
        "Midnight vessel, take me home",
        "Through the dark, through the drone",
        "I am the echo, I am the tone",
        "Sailing the frequencies alone",
        "Turn it up, let the silence break",
        "I feel the bassline in my chest",
        "Every heartbeat a waveform wake",
        "And I can't tell the noise from rest",
        "So play it louder, play it slow",
        "Let the current pull me under",
        "There's a chorus I don't know",
        "Singing softly like a thunder",
        "Static bloom, static bloom",
        "Petals of noise on the radio",
        "I hear you breathing in the tune",
        "Every word a faint halo",
        "Midnight vessel, take me home",
        "Through the dark, through the drone",
        "I am the echo, I am the tone",
        "Sailing the frequencies alone",
        "Fade into the carrier wave",
        "A million voices in the grave",
        "Of every song I never gave",
        "Midnight vessel, hear me call",
        "The static blooms and swallows all",
        "And when the morning finds me here",
        "I'll tune the dial and disappear",
        "Static bloom, static bloom",
    )

    /** A 240x240 gradient + bars cover, encoded as PNG bytes. */
    private fun synthCover(): ByteArray {
        val size = 240
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFF7C4DFF.toInt(), 0xFF00BFA5.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        val w = size / 9f
        for (i in 0 until 9) {
            val h = (size * (0.25f + 0.5f * ((i * 37) % 10) / 10f))
            c.drawRect(i * w + w * 0.25f, size - h, (i + 1) * w - w * 0.25f, size.toFloat(), bar)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** A [px]x[px] gradient cover, encoded as JPEG like a real song's embedded artwork. */
    private fun encodeCover(px: Int): ByteArray {
        val size = px.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0xFF7C4DFF.toInt(), 0xFF00BFA5.toInt(), 0xFFFF6E40.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }
}

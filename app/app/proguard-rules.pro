# ============================================================================
#  Ghostify — app-level R8/ProGuard rules (minify + resource shrink on release)
# ============================================================================
#
# Strategy: do NOT add broad -keep rules. The reflection-heavy frameworks in the
# stack (Room, WorkManager, Hilt, Media3, Chaquopy) each ship verified consumer
# rules in their AARs. We only add keeps for the parts of *our own code* that
# are reached reflectively — otherwise minification could strip them and R8
# would silently pass while the feature breaks at runtime.
#
# Consumer-rule libraries (no action needed here):
#   androidx.room, androidx.work, dagger.hilt, androidx.media3, kotlinx.coroutines

# --- Debuggability of release crash reports (Play console / adb logcat) ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- Reflection-safe attribute survival ---
# Libraries that reflect on generics/annotations need these; several ship their
# own, but the union is cheap and future-proof.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, MethodParameters

# --- Java/Kotlin classes resolved BY NAME from Python (ghostify_dl.py) ---
# The Python bridge hands PyObject callbacks back to Java and, in the other
# direction, spotdl callbacks must survive. Chaquopy resolves classes by name
# (Python.getModule / PyObject.toJava), which R8 cannot see statically.
# The python-runtime component owns com.ghostify.python; keep it intact.
-keep class com.ghostify.python.** { *; }

# ----------------------------------------------------------------------------
# Chaquopy / spotdl — see proguard-chaquopy.pro for the interpreter-specific
# rules. They are split out so a Chaquopy upgrade only touches one file.
# ----------------------------------------------------------------------------

# --- Native libs (ffmpeg, libpython.so) ---
# R8 cannot strip native libs, but never mangle native method bindings.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Media3 MediaButtonReceiver / MediaSessionService ---
# Covered by media3's consumer rules; nothing to add.

# --- Jetpack Glance home-screen widgets ---
# Glance resolves a widget's *instances* by the canonical name of its GlanceAppWidget class:
# `GlanceAppWidgetManager` persists a `receiver -> provider name` map (see
# androidx.glance.appwidget.GlanceAppWidgetManagerKt.reverseMapping) and hands it to
# `GlanceAppWidgetManager.updateAll` via the provider name. The library ships keeps for
# ActionCallback implementations for exactly this reason, but none for GlanceAppWidget
# itself, so without the two rules below R8 renamed — and on the release build shipped with
# the widgets even *merged* — PlayerWidget / PlaylistLauncherWidget / LibraryStatsWidget onto
# one name, which made all three receivers resolve to a single provider: a player repaint then
# painted the Playlists and Library host views with the player's composition. Keeping the
# class names stable keeps the receiver -> widget identity that Glance depends on.
# (The widget-update path additionally no longer relies on that map at all; see
# xyz.botolog.ghostify.widget.updateAllInstances.)
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# --- Rule diagnostics (uncomment to debug a "works in debug, breaks in release") ---
#-printmapping build/outputs/mapping/release/mapping.txt
#-printusage build/outputs/mapping/release/usage.txt

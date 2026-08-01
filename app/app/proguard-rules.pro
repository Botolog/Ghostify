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

# --- Rule diagnostics (uncomment to debug a "works in debug, breaks in release") ---
#-printmapping build/outputs/mapping/release/mapping.txt
#-printusage build/outputs/mapping/release/usage.txt

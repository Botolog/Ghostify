# ============================================================================
#  Chaquopy-specific R8 rules
# ============================================================================
#
# Chaquopy 17.0.0 already ships its own consumer rules (verified in
# gradle-plugin/src/main/kotlin/com/chaquo/python/proguard-rules.pro):
#
#   -keep class com.chaquo.python.** { *; }
#   -keep class kotlin.jvm.functions.** { *; }
#   -keep class kotlin.jvm.internal.FunctionBase { *; }
#   -keep class kotlin.reflect.KAnnotatedElement { *; }
#   -dontwarn org.jetbrains.annotations.NotNull
#
# Everything below is a defensive *extension* so that a Chaquopy upgrade that
# forgets to carry those rules cannot silently break the release build. The
# duplication is intentional and documented; if Chaquopy ships these again,
# R8 just sees harmless duplicate keeps.

# The interpreter + Java bridge are reached via JNI and module-name lookup,
# never statically, so they must survive shrink + rename.
-keep class com.chaquo.python.** { *; }

# Python-side code resolves Java functions through Chaquopy's reflection layer
# (getJavaMethod / jmethodID); keep their signatures.
-keepclasseswithmembers class com.chaquo.python.** {
    <methods>;
}

# The Kotlin SAM/callback types Chaquopy uses to call back from Python.
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.jvm.internal.FunctionBase { *; }

# Our Python bridge package (com.ghostify.python) — reached by name from the
# interpreter. Keeping it here, next to the Chaquopy rules, makes the contract
# obvious at release time.
-keep class com.ghostify.python.** { *; }

# spotdl/yt-dlp are pure Python source: R8 does not process them. The only
# Java-side reflection they touch is Chaquopy's own, covered above. No rules
# are needed for the Python packages themselves.

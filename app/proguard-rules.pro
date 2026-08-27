## MuPlay's R8 keep rules.
##
## Applied on top of `proguard-android-optimize.txt` and every dependency's own consumer rules --
## see `configureReleaseBuild` in build-logic for the wiring, and for why minification is on at all.
##
## The rule for this file: **every entry names the reflection it protects.** R8 in full mode removes
## anything no keep rule reaches, and the code this application reaches reflectively is exactly the
## code no JVM unit test and no debug run exercises. A rule with no stated reason is a rule nobody
## can ever delete, and a `-keep class **` that "fixes the crash" is how a minified build stops
## being minified one paste at a time.
##
## What is deliberately NOT here, because the library ships the rule itself as a consumer rule and
## AGP merges it in: Retrofit's own `META-INF/proguard/retrofit2.pro`, OkHttp's, Hilt/Dagger's,
## Room's, Coil's, and kotlinx-coroutines'. Re-stating those here would create a second copy that
## silently goes stale against the version in the catalogue. The entries below are the ones this
## application needs *beyond* them, plus the small number that are cheap insurance against a
## consumer rule changing under us in a library upgrade -- each marked as such.


## ---------------------------------------------------------------------------------------------
## Attributes.
## ---------------------------------------------------------------------------------------------

# `Signature` carries generic type arguments. Retrofit reads the return type of every interface
# method reflectively (`Call<List<Song>>`, `SubsonicResponse<...>`) and cannot pick a converter
# without it; kotlinx-serialization's runtime `serializer(KType)` lookup needs it for the same
# reason. Without this the app fails at the first API call with "Unable to create converter".
-keepattributes Signature

# The annotations Retrofit (`@GET`, `@Query`), kotlinx-serialization (`@Serializable`,
# `@SerialName`) and Dagger read at runtime. `RuntimeVisibleAnnotations` alone is not enough:
# Retrofit reads *parameter* annotations to build a request at all.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# Kotlin's own reflection entry point. `KClass.simpleName`, `Enum.valueOf` on a Kotlin enum and
# kotlinx-serialization's default `serialName` all read it.
-keepattributes InnerClasses,EnclosingMethod

# Line numbers in a crash report, mapped back through `build/outputs/mapping/release/mapping.txt`.
# `SourceFile` is renamed rather than kept so it carries no path from this machine.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


## ---------------------------------------------------------------------------------------------
## kotlinx.serialization.
## ---------------------------------------------------------------------------------------------
##
## Two consumers in this application, and the second is the one that breaks silently:
##
##  * `:core:network`'s Subsonic DTOs, through Retrofit's kotlinx-serialization converter. A break
##    here is loud -- the first request throws.
##  * `:app`'s navigation keys (`SetupRoute` and friends). `rememberNavBackStack` saves the back
##    stack through `rememberSaveable`, which means the serializer for a key class is looked up
##    when the *process is recreated*, not when the app starts. A break here survives every
##    smoke test and appears only after the system kills a backgrounded app.
##
## These are the rules from kotlinx.serialization's own README rather than invented ones. R8 has
## built-in kotlinx-serialization support and may well cover all of this on its own; that is
## precisely why the rules are cheap to keep and expensive to have guessed wrong about.

# The generated `Companion` that carries `serializer()`.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# The synthetic `$serializer` object itself, for every `@Serializable` class that has one.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# `@Serializable object`s -- `SetupRoute` and the other navigation keys are exactly this shape.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}


## ---------------------------------------------------------------------------------------------
## Room.
## ---------------------------------------------------------------------------------------------
##
## `Room.databaseBuilder(...)` resolves the generated implementation by name --
## `Class.forName(databaseClass.getCanonicalName() + "_Impl")` -- so obfuscating either half breaks
## the lookup with `Cannot find implementation for ...Database. ...Database_Impl does not exist`,
## at the first database access rather than at startup.
##
## room-runtime ships a consumer rule for this. Kept anyway: it is two lines, the failure it
## prevents is a crash on first use in a shipped build, and this project pins Room from a version
## catalogue that will move.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep,allowobfuscation @interface androidx.room.Database

# Room's generated `_Impl` reads the schema hash out of a string constant and compares it against
# `room_master_table`. Nothing reflective there -- but the migration classes are referenced only
# from the builder call site, so no rule is needed for them either. Recorded so the next person
# does not add one "just in case".


## ---------------------------------------------------------------------------------------------
## Media3 / ExoPlayer session plumbing.
## ---------------------------------------------------------------------------------------------
##
## `MuPlaybackService` itself is safe without a rule: it is declared in `:core:media`'s manifest,
## and AGP feeds every manifest-declared component to R8 as a keep. The parts that are *not* safe
## are the ones that cross the session IPC boundary, because those are reconstructed by name on
## the far side.

# `MediaSession`/`MediaController` move state across processes as `Bundle`s, and Media3's own
# `Bundleable`-style restoration reads a static `CREATOR`/`fromBundle` member reflectively. Media3
# ships consumer rules; this covers the `androidx.media3.common` types those rules have historically
# been written in terms of, so a rename in the library cannot quietly drop them.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# `DefaultRenderersFactory` loads the optional decoder extensions by name
# (`Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")` and friends). This app
# bundles none of them, so the lookups are *expected* to fail at runtime -- but R8 still sees the
# unresolved references at build time and warns. Silencing the warning is correct here; keeping
# the classes is impossible, since they are not on the classpath at all.
-dontwarn androidx.media3.decoder.**
-dontwarn androidx.media3.exoplayer.ext.**


## ---------------------------------------------------------------------------------------------
## OkHttp / Retrofit / Okio.
## ---------------------------------------------------------------------------------------------
##
## All three ship consumer rules. What those rules do NOT cover is the set of optional TLS
## providers OkHttp probes for by name at startup and this app does not bundle. Without these,
## R8 reports missing classes and fails the build outright (`-dontwarn` is required, not cosmetic).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

# A Retrofit service interface is implemented by a `java.lang.reflect.Proxy` built from the
# interface's own annotated methods. Erasing the annotations off those methods leaves an interface
# Retrofit can still see but can no longer build a request from. `allowshrinking`/`allowobfuscation`
# so an interface that genuinely becomes unused is still removed and the names are still renamed --
# it is the annotations that must survive, not the identifiers.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}


## ---------------------------------------------------------------------------------------------
## Hilt / Dagger.
## ---------------------------------------------------------------------------------------------
##
## Hilt's generated components are referenced directly by generated code, and `@AndroidEntryPoint`
## classes are bytecode-rewritten by the Hilt Gradle plugin before R8 ever sees them, so almost
## nothing needs a rule. The exception is the generated `Hilt_*` application/activity superclasses,
## which the *manifest* names indirectly (the plugin rewrites `android:name` to the generated
## class) -- covered by AGP's manifest keeps -- and this, which is not:

# `EntryPointAccessors.fromApplication(context, SomeEntryPoint::class.java)` resolves the
# implementation from the interface's own class object. Nothing in the source names the generated
# implementation, so R8 has no edge to follow to it.
-keep,allowobfuscation @interface dagger.hilt.EntryPoint
-keep @dagger.hilt.EntryPoint interface *

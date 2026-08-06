# Keep rules for the release build, which runs R8 in full mode (see gradle.properties).
#
# Every rule below names the mechanism that needs it. There is deliberately no blanket
# `-keep class com.gios.lightnotebook.**`: the point of turning minification on is that the
# unreachable half of the app goes away, and a wildcard keep on the app's own package undoes
# most of that. Anything not listed here is reached by an ordinary call and R8 can see it.
#
# light-common ships its own consumer rules for the wheel, the report queue and the LightSync
# provider, so nothing about those is repeated here.

# ---------------------------------------------------------------- stack traces

# Shake-to-report posts the crash stack into a GitHub issue, and an obfuscated stack is a wall
# of `a.a.a` that no amount of reading fixes. Line numbers cost a few KB in the APK and are the
# difference between a report that can be acted on and one that cannot.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- Room

# Room finds its generated implementation by name: `Room.databaseBuilder` ends up calling
# `Class.forName(<database class>_Impl)`. Neither the name nor the class is referenced from any
# code R8 can see, so full mode removes both. The constructor is spelled out because in full
# mode keeping a class no longer implies keeping its members.
-keep class com.gios.lightnotebook.data.NotebookDatabase { <init>(); }
-keep class com.gios.lightnotebook.data.NotebookDatabase_Impl { <init>(); }

# The generated `_Impl` for the DAO is instantiated by the database's generated code, and the
# generated code for both is annotation-driven output that R8 sees only through the reflective
# lookup above.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Dao class * { *; }

# Entities are constructed field-by-field by Room's generated cursor readers, and the migrations
# name their columns as SQL strings. Keeping the classes and their fields keeps the two in step;
# a renamed field with an unrenamed column is a runtime "no such column" and not a build error.
-keep @androidx.room.Entity class * { *; }
-keepclassmembers @androidx.room.Entity class * { <init>(...); }

# ---------------------------------------------------------------- WorkManager

# WorkManager stores the worker's fully-qualified class name in its own database and rebuilds
# the worker with `Class.forName(name).getDeclaredConstructor(Context, WorkerParameters)`. Work
# already scheduled by a previous install carries the *old* name, so this has to hold for
# classes nothing in this build points at either.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------- CameraX

# This app does not implement `CameraXConfig.Provider`, so CameraX picks its camera backend by
# reflecting on the name: `Class.forName("androidx.camera.camera2.Camera2Config")` and then its
# static `defaultConfig()`. camera-camera2 ships a consumer rule for this, but it is the one
# reflective edge in the whole camera path and its failure mode is a black screen on the capture
# screen rather than a build error, so it is pinned here too.
-keep class androidx.camera.camera2.Camera2Config { public static ** defaultConfig(); }

# ---------------------------------------------------------------- manifest components

# The activities, the alarm/boot/charge receivers and the LightSync provider are named as
# strings in AndroidManifest.xml. aapt2 already generates keep rules for those, so they are not
# repeated here — noted so the next person does not add them and assume they were load-bearing.
#
# The alarm PendingIntents are built with `Intent(context, ReminderReceiver::class.java)`, a
# class literal R8 follows, so they need nothing beyond the manifest rule either.

# ---------------------------------------------------------------- JSON

# Everything parsed from JSON here — Claude's vision response, open-meteo, Overpass, Nominatim,
# the queued report files — is read key by key through org.json, never mapped onto a class by
# field name. So there is nothing to keep, and no reflective binder to defeat. Written down
# because "we parse JSON" is the usual reason to reach for a keep rule, and it does not apply.

# ---------------------------------------------------------------- persisted enum names

# A repeat frequency is written into an `RRULE` string by name — `FREQ=WEEKLY` is
# `RepeatFreq.WEEKLY.name` — and read back with a `when` over the same spellings, in
# `util/Recurrence.kt`. Both the string in the database and the string in an imported `.ics`
# outlive the build that wrote them, so the names have to survive obfuscation; in full mode R8
# renames enum constants like anything else, and the failure is silent — every repeating event
# quietly stops repeating on the next update, with no crash to point at it.
#
# `values()` and `valueOf()` are kept as well as the fields, because R8's own enum-unboxing
# optimisation removes them otherwise and a future `RepeatFreq.valueOf(...)` here would
# disappear with them.
-keepclassmembers enum com.gios.lightnotebook.util.RepeatFreq {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

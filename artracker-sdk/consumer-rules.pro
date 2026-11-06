# WorkManager persists the worker class name; keep it stable across obfuscated builds.
-keepnames class id.art.tracker.transport.FlushWorker

# OkHttp, Fragment, Navigation and Compose are compileOnly: the host app may not ship them. Classes that refer to
# them are only loaded when ClassAvailability says the library is present, so R8 must not fail on the missing ones.
-dontwarn okhttp3.**
-dontwarn androidx.fragment.**
-dontwarn androidx.navigation.**
-dontwarn androidx.compose.**

# Class isolation: the holders below are the only classes that mention optional-library types. R8 may shrink and
# rename them, but must not inline or merge them into classes that load in an app without those libraries.
-keep,allowshrinking,allowobfuscation class id.art.tracker.lifecycle.FragmentSupport { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.lifecycle.FragmentTracker { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.lifecycle.NavBinder { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.replay.capture.ComposeWalker { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.replay.capture.ReflectiveSemanticsAccess { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.privacy.ComposeMaskRules { *; }

# Hosts add the interceptor by name; only its public no-arg constructor is part of the contract.
-keep class id.art.tracker.telemetry.okhttp.ARTrackerOkHttpInterceptor { public <init>(); }

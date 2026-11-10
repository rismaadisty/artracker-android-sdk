# Consumer rules of the ARTracker Android SDK. They are packaged into the AAR (proguard.txt) and applied by the host
# app's R8/ProGuard. Public API classes (ARTracker, ARTrackerConfig, ...) are reached by direct references from host
# code, so they need no keep rule and stay shrinkable; only reflection and class-isolation needs are listed here.

# WorkManager persists the worker class name; keep it stable across obfuscated builds.
-keepnames class id.art.tracker.transport.FlushWorker

# @ARTrackerScreenName is read at runtime with Class.getAnnotation: R8 must keep the annotation attribute and the
# annotation class (with its `value` member), or the override silently stops working in minified builds.
-keepattributes RuntimeVisibleAnnotations
-keep class id.art.tracker.lifecycle.ARTrackerScreenName { *; }

# OkHttp, Fragment, Navigation and Compose are compileOnly: the host app may not ship them. Classes that refer to
# them are only loaded when ClassAvailability says the library is present, so R8 must not fail on the missing ones.
# That covers the OkHttp interceptor (okhttp3.Interceptor, Request, Response), the Fragment/Navigation holders and
# the Compose mask classes listed below.
-dontwarn okhttp3.**
-dontwarn androidx.fragment.**
-dontwarn androidx.navigation.**
-dontwarn androidx.compose.**

# Class isolation: the holders below are the only classes that mention optional-library types. R8 may shrink and
# rename them, but must not inline or merge them into classes that load in an app without those libraries.
# ComposeMaskRulesKt is the Kotlin file facade of ComposeMaskRules.kt: it holds the top-level ARTrackerMaskKey
# (a Compose SemanticsPropertyKey) and is used by Modifier.artrackerMask()/artrackerUnmask().
-keep,allowshrinking,allowobfuscation class id.art.tracker.lifecycle.FragmentSupport { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.lifecycle.FragmentTracker { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.lifecycle.NavBinder { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.replay.screenshot.ReflectiveSemanticsAccess { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.replay.screenshot.ComposeMaskCollector { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.replay.screenshot.ComposeMaskCollectorFactory { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.privacy.ComposeMaskRules { *; }
-keep,allowshrinking,allowobfuscation class id.art.tracker.privacy.ComposeMaskRulesKt { *; }

# Hosts add the interceptor with `ARTrackerOkHttpInterceptor()`; only its public no-arg constructor is part of the
# contract. allowshrinking: the class is retained only if the host uses it, so apps without OkHttp do not keep it.
-keep,allowshrinking class id.art.tracker.telemetry.okhttp.ARTrackerOkHttpInterceptor { public <init>(); }

# Hosts pass their OkHttpClient: `ARTrackerOkHttpSender(client)`. Same reasoning as the interceptor: kept only if used.
-keep,allowshrinking class id.art.tracker.telemetry.okhttp.ARTrackerOkHttpSender { public <init>(okhttp3.OkHttpClient); }

# android.view.WindowManagerGlobal.mViews is read by reflection (RootViewLocator), but that is a framework class that
# R8 never renames, so no rule is needed.

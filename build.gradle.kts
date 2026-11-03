// No plugins are declared at the root on purpose.
// The Android Gradle Plugin is declared only in :app so that the platform-independent
// :core module can be built and tested without Google Maven / the Android SDK (ISKRA_CORE_ONLY=1).
// The Kotlin plugins must NOT be put on the root classpath either: the Kotlin Android plugin has to
// live in the same (or a child) classloader as AGP, otherwise it cannot see AGP classes
// (NoClassDefFoundError: com/android/build/gradle/BaseExtension). Each module therefore
// declares its own plugins; Gradle may log a harmless "Kotlin plugin loaded multiple times" warning.

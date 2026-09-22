# Tink uses reflection for its registries; keep it intact.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

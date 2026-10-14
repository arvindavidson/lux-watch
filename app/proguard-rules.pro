# Keep names readable in crash logs; R8 still shrinks and optimises.
-dontobfuscate

# Our code is small; keep it whole (JNI-free, but MuseSession/commands are
# reached through lambdas and the watch face/editor through the manifest).
-keep class com.luxwatch.gadget.** { *; }

# Bouncy Castle is used as a JCA provider: classes are found by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp's optional TLS providers, and annotations some libraries reference.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# Tink (behind EncryptedSharedPreferences, which holds the pairing): keep it
# whole so the stored keysets always decrypt with the same key managers.
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep Room generated implementations and entities' generic signatures.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher.
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ML Kit text recognition uses reflection for model loading.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

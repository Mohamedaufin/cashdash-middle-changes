# 🔥 Firebase & Google Play Services
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# 📷 ML Kit & Barcode Scanning
-keep class com.google.mlkit.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.mlkit.**

# 🛠 Kotlin & AndroidX
-keep class kotlin.reflect.jvm.internal.** { *; }
-keepclassmembers class ** {
    @androidx.annotation.Keep *;
}
-keep @androidx.annotation.Keep class *
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# 🗄 Room database class — this keep IS required.
# Room.databaseBuilder resolves the generated implementation by name at runtime
# (Class.forName("<YourDatabase>_Impl")), so the database class name must survive.
-keep class * extends androidx.room.RoomDatabase { *; }

# 🔒 Data model — deliberately NOT kept, so R8 obfuscates the schema.
#
# These used to carry -keep ... { *; }, which left every field name and the class
# structure readable in classes.dex. The comment justifying it said "Firestore
# serialization", but the codebase does no reflective mapping at all:
#
#   - toObject() / toObjects() are never called; Firestore documents are read
#     field by field with getString()/getLong()
#   - entities are built through explicit constructors, e.g. TransactionEntity(...)
#   - RTDB getValue() is only used with String::class.java and Long::class.java
#   - there is no Gson, Moshi, kotlinx.serialization or Jackson in the project
#
# Room needs no keep for entities either: its DAOs are generated at compile time
# and R8 renames the generated code and the entity consistently. Column names come
# from annotations and are baked into the generated SQL, so renaming a Kotlin field
# does not change the database schema.
#
# If reflective mapping is ever introduced — a toObject() call, or adding Gson —
# these keeps must come back, or fields will silently map to obfuscated names.

# 🖼 Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl

# 🎬 Lottie
-dontwarn com.airbnb.lottie.**
-keep class com.airbnb.lottie.** { *; }

# ✂️ Strip Debug Logs from Release Builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
}
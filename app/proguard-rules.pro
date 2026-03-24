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

# 🔒 CashDash Custom Models (Firestore serialization)
-keep class com.cash.dash.models.** { *; }

# ✂️ Strip Debug Logs from Release Builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
}
# CamGuard ProGuard Rules

# Keep model classes
-keep class com.camguard.app.models.** { *; }

# Keep Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Keep Billing
-keep class com.android.billingclient.** { *; }

# Keep AdMob
-keep class com.google.android.gms.ads.** { *; }

# Keep Parcelable
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

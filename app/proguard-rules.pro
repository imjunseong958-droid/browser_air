# GeckoView ships its JNI and engine consumer rules in its AAR.
# Preserve Parcelable creators used by Android's activity/session lifecycle.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

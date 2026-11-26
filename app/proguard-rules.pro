# Keep JNI native bridge and methods intact under R8 Full Mode
-keep class io.novadeck.control.core.NativeBridge {
    native <methods>;
}
-keepclassmembers class io.novadeck.control.core.NativeBridge {
    *;
}

# Strip debug logging in release for maximum speed and minimal APK footprint
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

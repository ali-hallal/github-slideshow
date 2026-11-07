# ProCam release build rules.
# Custom views are referenced from XML layouts by class name.
-keep class com.procam.app.** extends android.view.View { <init>(...); }
# ML Kit / Play services tasks ship their own consumer rules; silence optional-dependency warnings.
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**

# TensorFlow Lite (AI detail for super zoom): classes reached from native code.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

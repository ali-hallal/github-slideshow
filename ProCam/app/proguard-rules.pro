# ProCam release build rules.
# Custom views are referenced from XML layouts by class name.
-keep class com.procam.app.** extends android.view.View { <init>(...); }
# ML Kit / Play services tasks ship their own consumer rules; silence optional-dependency warnings.
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**

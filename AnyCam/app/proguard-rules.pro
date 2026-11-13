# AnyCam release build rules.
# Custom views are referenced from XML layouts by class name.
-keep class com.anycam.app.ui.** extends android.view.View { <init>(...); }
# The compiled merge is reached from Kotlin by name.
-keep class com.anycam.app.core.Native { *; }

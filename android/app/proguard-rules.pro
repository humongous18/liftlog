# Methods called from JavaScript must keep their names.
-keepclassmembers class com.betterlife.liftlog.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface,SourceFile,LineNumberTable

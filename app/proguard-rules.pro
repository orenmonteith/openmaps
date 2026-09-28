# Keep WebView JS bridge
-keepclassmembers class com.terrain.explorer.ui.TerrainJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

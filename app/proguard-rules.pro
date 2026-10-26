# The app uses no reflection of its own: the JavaScript bridge goes through
# androidx.webkit's WebMessageListener (a typed callback, not
# addJavascriptInterface), so nothing here has to be kept by name.
# OkHttp, Cronet and androidx ship their own consumer rules.

# Keep line numbers so a crash report from a tester points at real code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Add project specific ProGuard rules here.
# NewPipe's upstream release requires these for its Rhino-backed JS integration.
# Search itself does not invoke the stream decoder; keep these compatible for source initialization.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
# Android uses NewPipe's interpreted, safe-standard-object Rhino path. These desktop-only
# JavaBeans/optimizer integrations are not invoked there (matching upstream NewPipe rules).
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn jdk.dynalink.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

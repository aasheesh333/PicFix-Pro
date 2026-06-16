# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\someuser\AppData\Local\Android\Sdk\tools\proguard\proguard-android-optimize.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If you use reflectionlesssim you might want to retain the original symbol name.
#-keepattributes Signature

# For using GSON @Expose annotation
#-keepattributes *Annotation*

# For using Jetty client
#-dontwarn org.eclipse.jetty.**

# For using Jackson @Json... annotations
#-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
#-keepclassmembers,allowobfuscation class * {
#    @com.fasterxml.jackson.annotation.Json... *;
#}
# Retain all members of classes that implement android.os.Parcelable
#-keep class * implements android.os.Parcelable {
#  public static final android.os.Parcelable$Creator *;
#}
# Retain all members of classes that implement java.io.Serializable
#-keep class * implements java.io.Serializable {
#    private static final long serialVersionUID;
#    private void writeObject(java.io.ObjectOutputStream);
#    private void readObject(java.io.ObjectInputStream);
#    java.lang.Object writeReplace();
#    java.lang.Object readResolve();
#}

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# OpenCV (just in case)
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Coil (Image loading)
-keep class coil.** { *; }
-dontwarn coil.**

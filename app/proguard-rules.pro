# Mapping uploaded with the AAB for Play Console crash/ANR deobfuscation.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.gdreducacional.totemapp.NativeLogSilencer {
    *;
}

# ONNX Runtime JNI and session types.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Reflection used to quiet ML Kit / system logs.
-keep class com.google.mlkit.common.MlKitLogger { *; }
-keep class com.google.mlkit.common.sdkinternal.LogUtils { *; }
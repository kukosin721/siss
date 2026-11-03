# Reglas de R8 para el build de release.
#
# El release nunca se había compilado hasta ahora: sin estas reglas, R8 renombraba
# clases y métodos y rompía tres cosas que no se ven en tiempo de compilación:
#
#   1. JNI: los métodos `external fun` se buscan en la biblioteca nativa por nombre
#      (Java_com_kukosin_app_audio_AudioEngine_nativeCreate). Si R8 los renombra, el
#      arranque muere con UnsatisfiedLinkError.
#   2. Reflexión: NewPipeExtractor (YouTube Music) y Rhino cargan clases por nombre.
#   3. Servicios/Activitys declarados en el manifiesto y metadata de Kotlin.

# ── JNI ────────────────────────────────────────────────────────────────────────
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.kukosin.app.audio.** { *; }
-keep class com.kukosin.app.playback.FlacDecoderBridge { *; }
-keep class com.kukosin.app.playback.FlacDecoderBridge$* { *; }
-keep class com.kukosin.app.eq.** { *; }

# El código propio se conserva entero: es chico al lado de las librerías y sus nombres
# son contrato con el motor nativo (y con lo que se declara en el manifiesto).
-keep class com.kukosin.app.** { *; }

# ── Librerías que usan reflexión ───────────────────────────────────────────────
-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.**
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**
-keep class com.google.oboe.** { *; }
-keep class io.github.anilbeesetti.** { *; }
-dontwarn io.github.anilbeesetti.**

# ── General ────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep,allowobfuscation class kotlin.Metadata
-dontwarn javax.script.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

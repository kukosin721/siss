@file:Suppress("unused")
/** Stubs de `androidx.annotation`. */
package androidx.annotation

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FILE, AnnotationTarget.CONSTRUCTOR)
annotation class UnstableApi

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FILE)
annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<out Annotation>)

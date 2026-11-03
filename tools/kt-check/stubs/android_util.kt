@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

/** Stubs de `android.util` para el chequeo de tipos (no van al APK). */
object Log {
    fun v(tag: String, msg: String): Int = 0
    fun d(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String): Int = 0
}

class LruCache<K, V>(maxSize: Int) {
    fun get(key: K): V? = null
    fun put(key: K, value: V): V? = null
    fun evictAll() = Unit
    fun size(): Int = 0
}

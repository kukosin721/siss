@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.media3.common.util
annotation class UnstableApi

class ParsableByteArray(data: ByteArray? = null) {
    fun getPosition(): Int = 0
    fun setPosition(position: Int) = Unit
}

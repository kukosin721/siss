@file:Suppress("unused", "UNUSED_PARAMETER")
package android.os

object SystemClock {
    fun elapsedRealtime(): Long = 0L
}

open class Handler(looper: Looper? = null)

class Looper {
    companion object {
        fun getMainLooper(): Looper = Looper()
    }
}




interface Parcelable

object Build {
    class VERSION { companion object { const val SDK_INT = 34 } }
    class VERSION_CODES {
        companion object {
            const val S = 31
            const val TIRAMISU = 33
            const val P = 28
            const val UPSIDE_DOWN_CAKE = 34
        }
    }
}

class Bundle

class ParcelFileDescriptor : java.io.Closeable {
    fun detachFd(): Int = -1
    override fun close() = Unit

    companion object {
        const val MODE_READ_ONLY: Int = 268435456
        fun open(file: java.io.File, mode: Int): ParcelFileDescriptor = ParcelFileDescriptor()
        fun adoptFd(fd: Int): ParcelFileDescriptor = ParcelFileDescriptor()
    }
}

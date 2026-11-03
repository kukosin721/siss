@file:Suppress("unused", "UNUSED_PARAMETER")
package android.net

/** Stub de `android.net.Uri`: sólo lo que usan los archivos verificados. */
class Uri {
    val scheme: String? = null
    val path: String? = null
    val lastPathSegment: String? = null

    companion object {
        val EMPTY: Uri = Uri()
        fun parse(value: String): Uri = Uri()
    }
}

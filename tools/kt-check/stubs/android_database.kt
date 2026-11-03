@file:Suppress("unused", "UNUSED_PARAMETER", "ClassName")

package android.database

/** Stubs de `android.database` para el chequeo de tipos (no van al APK). */
interface Cursor {
    fun getColumnIndex(columnName: String): Int
    fun getColumnIndexOrThrow(columnName: String): Int
    fun isNull(columnIndex: Int): Boolean
    fun getInt(columnIndex: Int): Int
    fun getLong(columnIndex: Int): Long
    fun getString(columnIndex: Int): String?
    fun getCount(): Int
    fun moveToNext(): Boolean
    fun close()
}

/** `ContentObserver` de verdad vive acá. */
open class ContentObserver(handler: android.os.Handler?) {
    open fun onChange(selfChange: Boolean) {}
}

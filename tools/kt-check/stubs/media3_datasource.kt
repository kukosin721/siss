@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.media3.datasource

import androidx.media3.common.DataSpec

/** Stub de `androidx.media3.datasource.DataSource`. */
interface DataSource {
    fun open(dataSpec: DataSpec): Long
    fun close()
    fun interface Factory {
        fun createDataSource(): DataSource
    }
}

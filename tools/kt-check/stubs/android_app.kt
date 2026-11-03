@file:Suppress("unused", "UNUSED_PARAMETER", "ClassName")

package android.app

class PendingIntent {
    companion object {
        const val FLAG_MUTABLE = 1
        const val FLAG_UPDATE_CURRENT = 2
        fun getBroadcast(context: android.content.Context, code: Int, intent: android.content.Intent, flags: Int): PendingIntent = PendingIntent()
    }
}

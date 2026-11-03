@file:Suppress("unused", "UNUSED_PARAMETER", "ClassName")

package org.json

/**
 * Stubs de `org.json` (viene en Android, no en la JVM pura) para el chequeo de tipos.
 * Sólo declaran la API que se usa; no hay comportamiento.
 */
class JSONException(message: String = "") : Exception(message)

open class JSONObject {
    constructor()
    constructor(text: String)

    fun put(name: String, value: Boolean): JSONObject = this
    fun put(name: String, value: Int): JSONObject = this
    fun put(name: String, value: Long): JSONObject = this
    fun put(name: String, value: Double): JSONObject = this
    fun put(name: String, value: Any?): JSONObject = this
    fun optInt(name: String): Int = 0
    fun optInt(name: String, fallback: Int): Int = fallback
    fun optLong(name: String): Long = 0L
    fun optLong(name: String, fallback: Long): Long = fallback
    fun optString(name: String): String = ""
    fun optString(name: String, fallback: String): String = fallback
    fun optBoolean(name: String, fallback: Boolean): Boolean = fallback
    fun optJSONArray(name: String): JSONArray? = null
    fun optJSONObject(name: String): JSONObject? = null
    override fun toString(): String = ""
}

open class JSONArray {
    constructor()
    constructor(text: String)

    fun put(value: Any?): JSONArray = this
    fun length(): Int = 0
    fun optJSONObject(index: Int): JSONObject? = null
    override fun toString(): String = ""
}

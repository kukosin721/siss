/*
 * Verificación sin Android del ecualizador: perfiles por salida.
 *
 * Se compila junto con eq/Biquad.kt, eq/EqModel.kt, eq/EqEngine.kt y eq/EqManager.kt
 * usando stubs mínimos de android.content (están en tools/android-stubs). No forma
 * parte de la app: es un banco de pruebas para el que quiera correrlo sin SDK.
 *
 *   kotlinc -cp kotlinx-coroutines-core-jvm.jar:stubs_out -d out \
 *     tools/EqProfileHarness.kt eq/*.kt
 *   java -cp out:kotlin-stdlib.jar:kotlinx-coroutines-core-jvm.jar:stubs_out HarnessKt
 */
import android.content.Context
import android.content.SharedPreferences
import com.kukosin.app.eq.EqBandType
import com.kukosin.app.eq.EqManager
import com.kukosin.app.eq.EqProfile

class FakePrefs : SharedPreferences {
    val data = HashMap<String, Any?>()
    override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
    override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
    override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
    override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
    override fun contains(key: String) = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = ArrayList<String>()
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        fun remove(key: String) = apply { removed += key }
        fun clear() = apply { pending.clear(); removed.addAll(data.keys) }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            removed.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}

val fake = FakePrefs()
class FakeContext : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = fake
    override fun getApplicationContext(): Context = this
}

fun check(name: String, condition: Boolean) {
    if (!condition) { println("FALLA: $name"); System.exit(1) } else println("ok  $name")
}

fun main() {
    // Migración del formato viejo (una sola curva) al perfil de auriculares.
    fake.data["enabled"] = true
    fake.data["preamp"] = -3f
    fake.data["gain_0"] = 4f

    EqManager.init(FakeContext())
    check("perfil inicial = auriculares", EqManager.profile.value == EqProfile.WIRED)
    check("migró enabled", EqManager.settings.value.enabled)
    check("migró preamp", EqManager.settings.value.preampDb == -3f)
    check("migró banda", EqManager.settings.value.bands[0].gainDb == 4f)

    // Cada salida guarda su propia curva.
    EqManager.setProfile(EqProfile.USB)
    check("USB arranca plano", !EqManager.settings.value.enabled && EqManager.settings.value.bands[0].gainDb == 0f)
    EqManager.setEnabled(true)
    EqManager.setBandGain(0, 9f)
    EqManager.setBandType(0, EqBandType.HIGH_SHELF)
    check("tipo de banda aplicado", EqManager.settings.value.bands[0].type == EqBandType.HIGH_SHELF)
    check("snapshot del hilo de audio sigue el perfil", EqManager.snapshot.bands[0].gainDb == 9f)

    EqManager.setProfile(EqProfile.WIRED)
    check("volvió la curva de auriculares", EqManager.settings.value.bands[0].gainDb == 4f && EqManager.settings.value.enabled)
    check("snapshot volvió también", EqManager.snapshot.bands[0].gainDb == 4f)

    EqManager.setProfile(EqProfile.BUILTIN)
    check("altavoz arranca plano", !EqManager.settings.value.enabled)

    EqManager.setProfile(EqProfile.USB)
    check("USB conserva lo suyo", EqManager.settings.value.bands[0].gainDb == 9f)
    check("se persistió el tipo con prefijo", fake.data["usb_type_0"] == "HIGH_SHELF")
    check("se persistió la ganancia con prefijo", fake.data["usb_gain_0"] == 9f)
    check("el perfil viejo no se pisó pisado", fake.data["wired_gain_0"] == 4f)
    println("TODO OK")
}

package ir.bazr.inspection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private AES-GCM storage; backups are disabled in AndroidManifest. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("bazr_secure_v2", Context.MODE_PRIVATE)
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val plainAlias = "bazr_local_aes_v2"
    private val bioAlias = "bazr_bio_aes_v2"

    private fun getKey(alias: String, biometric: Boolean): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (biometric) {
            builder.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun encryptedPair(text: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(plainAlias, false))
        val raw = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Pair(enc(cipher.iv), enc(raw))
    }

    fun save(name: String, text: String) {
        val (iv, ciphertext) = encryptedPair(text)
        prefs.edit().putString("$name.iv", iv).putString("$name.ct", ciphertext).apply()
    }

    /** Synchronous durable write for queue records, signatures and evidence metadata. */
    fun saveDurable(name: String, text: String) {
        val (iv, ciphertext) = encryptedPair(text)
        if (!prefs.edit().putString("$name.iv", iv).putString("$name.ct", ciphertext).commit()) {
            throw IOException("ذخیره پایدار اطلاعات رمزنگاری‌شده انجام نشد")
        }
    }

    /** Strict read for irreplaceable user data: corruption must never look like an empty draft. */
    fun loadStrict(name: String): String? {
        val iv = prefs.getString("$name.iv", null)
        val ct = prefs.getString("$name.ct", null)
        if (iv == null && ct == null) return null
        if (iv.isNullOrBlank() || ct.isNullOrBlank()) throw IOException("رکورد رمزنگاری‌شده ناقص است")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(plainAlias, false), GCMParameterSpec(128, dec(iv)))
            String(cipher.doFinal(dec(ct)), Charsets.UTF_8)
        } catch (error: Exception) {
            throw IOException("بازیابی امن اطلاعات ذخیره‌شده ناموفق بود", error)
        }
    }

    /** Compatibility read for disposable cache/index data. */
    fun load(name: String): String? = try { loadStrict(name) } catch (_: Exception) { null }

    fun hasEncryptedRecord(name: String): Boolean =
        prefs.contains("$name.iv") || prefs.contains("$name.ct")

    fun encryptedRecordNames(prefix: String): List<String> = prefs.all.keys
        .filter { it.startsWith(prefix) && (it.endsWith(".iv") || it.endsWith(".ct")) }
        .map { it.removeSuffix(".iv").removeSuffix(".ct") }
        .distinct()
        .sorted()

    fun clear(name: String) { prefs.edit().remove("$name.iv").remove("$name.ct").apply() }
    fun clearDurable(name: String) {
        if (!prefs.edit().remove("$name.iv").remove("$name.ct").commit()) {
            throw IOException("پاک‌سازی پایدار اطلاعات رمزنگاری‌شده انجام نشد")
        }
    }
    fun getString(name: String): String? = prefs.getString(name, null)
    fun putString(name: String, value: String) { prefs.edit().putString(name, value).apply() }
    fun putStringDurable(name: String, value: String) {
        if (!prefs.edit().putString(name, value).commit()) throw IOException("ذخیره پایدار تنظیمات انجام نشد")
    }
    fun removeString(name: String) { prefs.edit().remove(name).apply() }
    fun removeStringDurable(name: String) {
        if (!prefs.edit().remove(name).commit()) throw IOException("حذف پایدار تنظیمات انجام نشد")
    }
    fun biometricEnabled(): Boolean = prefs.getBoolean("bio.enabled", false)

    fun bioEncryptCipher(): Cipher {
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getKey(bioAlias, true)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // A user may have enrolled/removed biometrics while the app was installed.
            // The caller still has the authenticated device token in memory and can bind a fresh key.
            clearBio()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getKey(bioAlias, true)) }
        }
    }

    fun bioDecryptCipher(): Cipher? {
        val iv = prefs.getString("bio.iv", null)
        val ct = prefs.getString("bio.ct", null)
        if (iv == null && ct == null) {
            if (biometricEnabled()) clearBio()
            return null
        }
        if (iv.isNullOrBlank() || ct.isNullOrBlank()) { clearBio(); return null }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, getKey(bioAlias, true), GCMParameterSpec(128, dec(iv)))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            clearBio()
            null
        } catch (_: Exception) {
            clearBio()
            null
        }
    }

    fun finalizeBioEncryption(cipher: Cipher, token: String) {
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val saved = prefs.edit().putString("bio.iv", enc(cipher.iv)).putString("bio.ct", enc(encrypted))
            .putBoolean("bio.enabled", true).commit()
        if (!saved) throw IOException("ذخیره امن ورود بیومتریک انجام نشد")
        clearDurable("device_token")
    }

    fun decryptBio(cipher: Cipher): String {
        val raw = dec(prefs.getString("bio.ct", null) ?: error("bio token unavailable"))
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    fun clearBio() {
        prefs.edit().remove("bio.iv").remove("bio.ct").putBoolean("bio.enabled", false).commit()
        runCatching { if (ks.containsAlias(bioAlias)) ks.deleteEntry(bioAlias) }
    }

    private fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

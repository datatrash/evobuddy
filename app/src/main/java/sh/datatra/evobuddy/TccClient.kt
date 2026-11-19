package sh.datatra.evobuddy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.time.Instant

/** Tokens only. The TCC password is used for login and is never persisted. */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("evobuddy-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("evobuddy-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? {
        val saved = prefs.getString("session", null) ?: return null
        return try {
            val parts = saved.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) { clear(); null }
    }
    fun save(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("session", encoded).commit()) { "Could not securely save this session." }
    }
    fun clear() { prefs.edit().clear().commit() }
}
data class HttpResult(val code: Int, val body: String)
fun interface Transport { fun send(method: String, path: String, headers: Map<String, String>, body: String?): HttpResult }
class CloudTransport : Transport {
    override fun send(method: String, path: String, headers: Map<String, String>, body: String?): HttpResult {
        val connection = URL("https://tccna.resideo.com/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(code, text)
        } finally { connection.disconnect() }
    }
}
class CloudException(message: String, val status: Int = 0) : Exception(message)
class TccClient(
    private val transport: Transport = CloudTransport(),
    private val removeToken: () -> Unit = {},
    private val saveToken: (JSONObject) -> Unit = {}
) {
    @Volatile var session: JSONObject? = null
    private val sessionLock = Any()
    private var sessionGeneration = 0
    private val _sessionRejected = MutableStateFlow(false)
    val sessionRejected = _sessionRejected.asStateFlow()
    @Volatile private var blockedUntil = Instant.EPOCH
    private data class Credentials(val generation: Int, val token: JSONObject)

    fun clearSession() = synchronized(sessionLock) { sessionGeneration++; session = null; _sessionRejected.value = false }
    fun replaceSession(token: JSONObject) = synchronized(sessionLock) {
        saveToken(token)
        sessionGeneration++
        session = token
        _sessionRejected.value = false
    }
    private fun checkRateLimit() { if (Instant.now() < blockedUntil) throw CloudException("TCC is limiting requests. Wait a minute before trying again.", 429) }
    fun login(username: String, password: String) {
        replaceSession(authenticate(mapOf("grant_type" to "password", "Username" to username.trim(), "Password" to password)))
    }
    private fun authenticate(fields: Map<String, String>): JSONObject {
        checkRateLimit()
        val application = java.util.Base64.getEncoder().encodeToString("4a231089-d2b6-41bd-a5eb-16a0a422b999:1a15cdb8-42de-407b-add0-059f92c530cb".toByteArray())
        val form = (fields + ("scope" to "EMEA-V1-Basic EMEA-V1-Anonymous")).entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val response = transport.send("POST", "Auth/OAuth/Token", mapOf("Authorization" to "Basic $application", "Accept" to "application/json", "Content-Type" to "application/x-www-form-urlencoded", "Cache-Control" to "no-cache, no-store"), form)
        if (response.code !in 200..299) fail(response.code, auth = true)
        val json = JSONObject(response.body)
        if (json.isNull("refresh_token") || json.optString("refresh_token").isBlank()) json.put("refresh_token", fields["refresh_token"])
        require(!json.isNull("access_token") && json.optString("access_token").isNotBlank() && !json.isNull("refresh_token")) {
            "TCC returned an invalid session."
        }
        return json.put("expiresAt", Instant.now().plusSeconds(json.getLong("expires_in") - 30).toString())
    }
    private fun credentials(): Credentials = synchronized(sessionLock) {
        Credentials(sessionGeneration, session ?: throw CloudException("Please connect your TCC account.", 401))
    }

    private fun rejectSession(rejected: Credentials) = synchronized(sessionLock) {
        if (sessionGeneration == rejected.generation && session === rejected.token) {
            session = null
            sessionGeneration++
            removeToken()
            _sessionRejected.value = true
        }
    }

    @Synchronized private fun refreshToken(previous: Credentials): Credentials {
        val current = credentials()
        check(current.generation == previous.generation) { "The TCC session changed." }
        if (current.token !== previous.token) return current
        val refresh = current.token.optString("refresh_token")
        val renewed = try {
            if (refresh.isBlank()) throw CloudException("Missing refresh token.", 401)
            authenticate(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
        } catch (error: CloudException) {
            if (error.status != 400 && error.status != 401) throw error
            rejectSession(current)
            throw CloudException("Your TCC session has expired. Reconnect your account.", 401)
        }
        return synchronized(sessionLock) {
            check(sessionGeneration == current.generation) { "The TCC session changed." }
            saveToken(renewed)
            session = renewed
            Credentials(current.generation, renewed)
        }
    }
    private fun fail(code: Int, auth: Boolean = false): Nothing {
        if (code == 429) blockedUntil = Instant.now().plusSeconds(60)
        throw CloudException(when {
            code == 429 -> "TCC is limiting requests. Wait a minute before trying again."
            code == 401 || auth && code == 400 -> "Your TCC session or login was not accepted. Please sign in again."
            code == 403 -> "TCC refused access. Check this account in the official TCC app."
            code in 500..599 -> "The TCC cloud is unavailable. Please try again later."
            else -> "TCC could not complete this request (HTTP $code)."
        }, code)
    }
    fun request(method: String, path: String, body: JSONObject? = null): String {
        checkRateLimit()
        fun send(auth: Credentials): HttpResult {
            checkRateLimit()
            check(synchronized(sessionLock) { sessionGeneration == auth.generation }) { "The TCC session changed." }
            return transport.send(method, "WebAPI/emea/api/v1/$path", mapOf("Authorization" to "bearer ${auth.token.getString("access_token")}", "Accept" to "application/json", "Content-Type" to "application/json"), body?.toString())
        }
        var auth = credentials()
        if (auth.token.optString("expiresAt").toInstantOrNull()?.isAfter(Instant.now()) != true) auth = refreshToken(auth)
        var result = send(auth)
        // A rejected authorization request was not applied; refresh once, never retry timeouts or 5xx writes.
        if (result.code == 401) {
            auth = refreshToken(auth)
            result = send(auth)
            if (result.code == 401) rejectSession(auth)
        }
        if (result.code !in 200..299) fail(result.code)
        return result.body
    }
    fun accountId(): String = JSONObject(request("GET", "userAccount")).getString("userId")
    fun discover(user: String = accountId()): List<Home> {
        return parseHomes(JSONArray(request("GET", "location/installationInfo?userId=${URLEncoder.encode(user, "UTF-8")}&includeTemperatureControlSystems=True")))
    }
    fun status(home: Home): JSONObject = JSONObject(request("GET", "location/${home.locationId}/status?includeTemperatureControlSystems=True"))
    fun schedule(room: Room): WeekSchedule = WeekSchedule.parse(JSONObject(request("GET", "temperatureZone/${room.id}/schedule"))).also { it.validate(room) }
    fun override(room: Room, temperature: Double?, until: Instant?, resume: Boolean = false): String {
        val mode = if (resume) "FollowSchedule" else if (until == null) "PermanentOverride" else "TemporaryOverride"
        require(mode in room.allowed) { "This room does not support that override type." }
        require(resume || temperature != null && room.validTemperature(temperature)) { "Choose a supported temperature." }
        require(until == null || until > Instant.now()) { "Choose an end time in the future." }
        val body = JSONObject().put("setpointMode", mode)
        if (!resume) body.put("heatSetpointValue", temperature)
        if (until != null) body.put("timeUntil", until.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
        return request("PUT", "temperatureZone/${room.id}/heatSetpoint", body)
    }
    fun setMode(home: Home, option: ModeOption, until: Instant?): String {
        require(option in home.modes && (if (until == null) option.permanent else option.temporary)) { "That mode duration is not supported." }
        require(until == null || until > Instant.now()) { "Choose an end time in the future." }
        val body = JSONObject().put("systemMode", option.id).put("permanent", until == null)
        if (until != null) body.put("timeUntil", until.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
        return request("PUT", "temperatureControlSystem/${home.systemId}/mode", body)
    }
    fun saveSchedule(room: Room, schedule: WeekSchedule): String { schedule.validate(room); return request("PUT", "temperatureZone/${room.id}/schedule", schedule.json()) }
    fun waitTask(response: String): Boolean {
        // HTTP acceptance is not hardware confirmation. A returned commTaskId permits controller acknowledgement.
        val json = runCatching { JSONObject(response) }.getOrNull() ?: return false
        val id = json.optString("id", json.optString("commtaskId"))
        if (id.isBlank()) return false
        repeat(5) {
            Thread.sleep(2000)
            val task = JSONObject(request("GET", "commTasks?commTaskId=${URLEncoder.encode(id, "UTF-8")}"))
            when (task.optString("state")) {
                "Succeeded" -> return true
                "Failed", "TimedOut" -> return false
            }
        }
        return false
    }
}

package com.miller.tpics.data

import android.content.Context
import android.content.SharedPreferences
import com.miller.tpics.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/* ───────────────────────────── HTTP ───────────────────────────── */

class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HttpError(r.code, body)
            body
        }
    }
}

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Mensaje corto y entendible para mostrar al usuario. */
fun Throwable.friendly(): String = when (this) {
    is AuthError -> message ?: "No se pudo iniciar sesión"
    is HttpError -> when (code) {
        401, 403 -> "No tienes permiso para esto"
        402 -> "Tu cuenta no está activa"
        429 -> "Telegram pide esperar un momento"
        else -> "Error del servidor ($code)"
    }
    is java.net.UnknownHostException, is java.net.ConnectException,
    is java.net.SocketTimeoutException -> "Sin conexión a internet"
    else -> message ?: "Algo salió mal"
}

/* ─────────────────────────── Preferencias ─────────────────────────── */

object Prefs {
    lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("tpics", Context.MODE_PRIVATE)
        theme.value = sp.getInt("theme", 0)
        columns.value = sp.getInt("columns", 4)
        wifiOnly.value = sp.getBoolean("wifiOnly", false)
    }

    /** 0 = según el sistema, 1 = claro, 2 = oscuro */
    val theme = MutableStateFlow(0)
    val columns = MutableStateFlow(4)
    val wifiOnly = MutableStateFlow(false)

    fun setTheme(v: Int) { theme.value = v; sp.edit().putInt("theme", v).apply() }
    fun setColumns(v: Int) { columns.value = v; sp.edit().putInt("columns", v).apply() }
    fun setWifiOnly(v: Boolean) { wifiOnly.value = v; sp.edit().putBoolean("wifiOnly", v).apply() }
}

/* ─────────────────────── Sesión (Firebase Auth) ─────────────────────── */

class AuthError(message: String) : Exception(message)

data class Session(
    val uid: String,
    val email: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

object Auth {
    val session = MutableStateFlow<Session?>(null)
    private val mutex = Mutex()
    private const val BASE = "https://identitytoolkit.googleapis.com/v1/accounts"

    fun init() {
        val sp = Prefs.sp
        val uid = sp.getString("uid", null) ?: return
        session.value = Session(
            uid = uid,
            email = sp.getString("email", "") ?: "",
            idToken = sp.getString("idToken", "") ?: "",
            refreshToken = sp.getString("refreshToken", "") ?: "",
            expiresAt = sp.getLong("expiresAt", 0),
        )
    }

    private fun save(s: Session?) {
        session.value = s
        val e = Prefs.sp.edit()
        if (s == null) {
            e.remove("uid").remove("email").remove("idToken").remove("refreshToken").remove("expiresAt")
        } else {
            e.putString("uid", s.uid).putString("email", s.email).putString("idToken", s.idToken)
                .putString("refreshToken", s.refreshToken).putLong("expiresAt", s.expiresAt)
        }
        e.apply()
    }

    private fun translate(code: String): String = when {
        code.startsWith("EMAIL_NOT_FOUND") || code.startsWith("INVALID_PASSWORD") ||
            code.startsWith("INVALID_LOGIN_CREDENTIALS") -> "Correo o contraseña incorrectos"
        code.startsWith("EMAIL_EXISTS") -> "Ya existe una cuenta con ese correo"
        code.startsWith("WEAK_PASSWORD") -> "La contraseña debe tener al menos 6 caracteres"
        code.startsWith("INVALID_EMAIL") -> "El correo no es válido"
        code.startsWith("USER_DISABLED") -> "Esta cuenta fue desactivada"
        code.startsWith("TOO_MANY_ATTEMPTS") -> "Demasiados intentos. Prueba más tarde"
        code.startsWith("OPERATION_NOT_ALLOWED") || code.startsWith("CONFIGURATION_NOT_FOUND") ->
            "Falta activar el inicio con correo en Firebase"
        else -> "No se pudo completar ($code)"
    }

    private suspend fun call(method: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$BASE:$method?key=${BuildConfig.FB_API_KEY}")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        try {
            return JSONObject(Http.exec(req))
        } catch (e: HttpError) {
            val code = runCatching {
                JSONObject(e.body).getJSONObject("error").getString("message")
            }.getOrDefault("ERROR")
            throw AuthError(translate(code))
        }
    }

    private fun fromLogin(o: JSONObject): Session = Session(
        uid = o.getString("localId"),
        email = o.optString("email"),
        idToken = o.getString("idToken"),
        refreshToken = o.getString("refreshToken"),
        expiresAt = System.currentTimeMillis() + o.optString("expiresIn", "3600").toLong() * 1000,
    )

    suspend fun signIn(email: String, password: String) {
        val o = call(
            "signInWithPassword",
            JSONObject().put("email", email).put("password", password).put("returnSecureToken", true),
        )
        save(fromLogin(o))
    }

    suspend fun signUp(email: String, password: String) {
        val o = call(
            "signUp",
            JSONObject().put("email", email).put("password", password).put("returnSecureToken", true),
        )
        save(fromLogin(o))
    }

    suspend fun resetPassword(email: String) {
        call("sendOobCode", JSONObject().put("requestType", "PASSWORD_RESET").put("email", email))
    }

    fun signOut() = save(null)

    /** Token vigente; se renueva solo cuando está por vencer. */
    suspend fun token(): String = mutex.withLock {
        val s = session.value ?: throw AuthError("Inicia sesión de nuevo")
        if (s.expiresAt - System.currentTimeMillis() > 5 * 60_000) return@withLock s.idToken

        val req = Request.Builder()
            .url("https://securetoken.googleapis.com/v1/token?key=${BuildConfig.FB_API_KEY}")
            .post(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", s.refreshToken)
                    .build()
            )
            .build()
        val o = try {
            JSONObject(Http.exec(req))
        } catch (e: HttpError) {
            if (e.code == 400 || e.code == 401 || e.code == 403) {
                save(null)
                throw AuthError("Tu sesión venció. Inicia sesión de nuevo")
            }
            throw e
        }
        val fresh = s.copy(
            idToken = o.getString("id_token"),
            refreshToken = o.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() + o.optString("expires_in", "3600").toLong() * 1000,
        )
        save(fresh)
        fresh.idToken
    }

    fun tokenBlocking(): String = runBlocking { token() }
}

/* ─────────────────── Base de datos (Firebase RTDB, REST) ─────────────────── */

object Db {
    private val BASE = BuildConfig.DB_URL.trimEnd('/')

    private suspend fun url(path: String, query: String = ""): String =
        "$BASE/$path.json?auth=${enc(Auth.token())}$query"

    /** Devuelve JSONObject, JSONArray, Boolean, Number, String o null. */
    suspend fun get(path: String, query: String = ""): Any? {
        val text = Http.exec(Request.Builder().url(url(path, query)).build())
        val v = JSONTokener(text).nextValue()
        return if (v == JSONObject.NULL) null else v
    }

    suspend fun getObject(path: String, query: String = ""): JSONObject? = get(path, query) as? JSONObject

    suspend fun put(path: String, json: String) {
        Http.exec(Request.Builder().url(url(path)).put(json.toRequestBody(Http.JSON)).build())
    }

    suspend fun patch(path: String, json: String) {
        Http.exec(Request.Builder().url(url(path)).patch(json.toRequestBody(Http.JSON)).build())
    }

    suspend fun delete(path: String) {
        Http.exec(Request.Builder().url(url(path)).delete().build())
    }

    /** Lee una base antigua pública (sin sesión). */
    suspend fun getOpen(fullUrl: String): Any? {
        val text = Http.exec(Request.Builder().url(fullUrl).build())
        val v = JSONTokener(text).nextValue()
        return if (v == JSONObject.NULL) null else v
    }

    val SERVER_TIME: JSONObject get() = JSONObject().put(".sv", "timestamp")
}

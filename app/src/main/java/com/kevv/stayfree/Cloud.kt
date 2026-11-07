package com.kevv.stayfree

import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Email/password login (Firebase Auth REST) + cloud backup (Firestore REST). No SDK needed. */
object Cloud {
    data class Session(val uid: String, val email: String, val idToken: String, val refreshToken: String, val expiresAt: Long)

    val configured: Boolean get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()
    private val key get() = BuildConfig.FIREBASE_API_KEY
    private val pid get() = BuildConfig.FIREBASE_PROJECT_ID

    // ---- local session storage ----
    fun load(p: SharedPreferences): Session? {
        val uid = p.getString("uid", null) ?: return null
        return Session(uid, p.getString("email", "") ?: "", p.getString("idToken", "") ?: "",
            p.getString("refresh", "") ?: "", p.getLong("expiresAt", 0))
    }

    fun save(p: SharedPreferences, s: Session) {
        p.edit().putString("uid", s.uid).putString("email", s.email).putString("idToken", s.idToken)
            .putString("refresh", s.refreshToken).putLong("expiresAt", s.expiresAt).apply()
    }

    fun clear(p: SharedPreferences) {
        p.edit().remove("uid").remove("email").remove("idToken").remove("refresh").remove("expiresAt").apply()
    }

    // ---- HTTP ----
    private fun call(method: String, url: String, body: String? = null, token: String? = null, form: Boolean = false): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 15000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: IOException) {
            Result.failure(Exception("No internet connection."))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Unexpected error."))
        }
    }

    private fun authError(text: String): String {
        val m = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("")
        return when {
            m.startsWith("EMAIL_EXISTS") -> "That email already has an account. Use Sign in."
            m.startsWith("INVALID_LOGIN_CREDENTIALS") || m.startsWith("INVALID_PASSWORD") ||
                m.startsWith("EMAIL_NOT_FOUND") -> "Wrong email or password."
            m.startsWith("WEAK_PASSWORD") -> "Password must be at least 6 characters."
            m.startsWith("INVALID_EMAIL") -> "That email address isn't valid."
            m.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts. Try again later."
            m.startsWith("OPERATION_NOT_ALLOWED") -> "Email/password sign-in isn't enabled in your Firebase project."
            m.startsWith("API_KEY") || m.contains("API key") -> "The Firebase API key in firebase.properties is wrong."
            m.isBlank() -> "Request failed."
            else -> m
        }
    }

    private fun sessionFrom(o: JSONObject, email: String) = Session(
        o.getString("localId"), o.optString("email", email), o.getString("idToken"),
        o.getString("refreshToken"), System.currentTimeMillis() + o.getString("expiresIn").toLong() * 1000
    )

    private fun auth(endpoint: String, email: String, pw: String): Session {
        val body = JSONObject().put("email", email).put("password", pw).put("returnSecureToken", true).toString()
        val (code, text) = call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:$endpoint?key=$key", body)
        if (code !in 200..299) throw Exception(authError(text))
        return sessionFrom(JSONObject(text), email)
    }

    suspend fun signUp(email: String, pw: String) = io { auth("signUp", email.trim(), pw) }
    suspend fun signIn(email: String, pw: String) = io { auth("signInWithPassword", email.trim(), pw) }

    suspend fun resetPassword(email: String) = io {
        val body = JSONObject().put("requestType", "PASSWORD_RESET").put("email", email.trim()).toString()
        val (code, text) = call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$key", body)
        if (code !in 200..299) throw Exception(authError(text))
    }

    suspend fun refresh(s: Session) = io {
        val form = "grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(s.refreshToken, "UTF-8")
        val (code, text) = call("POST", "https://securetoken.googleapis.com/v1/token?key=$key", form, form = true)
        if (code !in 200..299) throw Exception("Session expired. Please sign in again.")
        val o = JSONObject(text)
        Session(s.uid, s.email, o.getString("id_token"), o.getString("refresh_token"),
            System.currentTimeMillis() + o.getString("expires_in").toLong() * 1000)
    }

    // ---- Firestore backup: one document users/{uid} ----
    private fun base() = "https://firestore.googleapis.com/v1/projects/$pid/databases/(default)/documents"

    suspend fun upload(s: Session, json: String) = io {
        val name = "projects/$pid/databases/(default)/documents/users/${s.uid}"
        val fields = JSONObject()
            .put("data", JSONObject().put("stringValue", json))
            .put("updatedAt", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
        val body = JSONObject().put("writes", JSONArray().put(
            JSONObject().put("update", JSONObject().put("name", name).put("fields", fields))
        )).toString()
        val (code, text) = call("POST", "${base()}:commit", body, s.idToken)
        if (code !in 200..299) throw Exception(
            if (code == 403) "Cloud database refused the backup. Check your Firestore rules (see SETUP_CLOUD.md)."
            else "Backup failed ($code)."
        )
        Unit
    }

    /** null = no backup exists yet */
    suspend fun download(s: Session) = io<String?> {
        val (code, text) = call("GET", "${base()}/users/${s.uid}", token = s.idToken)
        when {
            code == 404 -> null
            code == 403 -> throw Exception("Cloud database refused access. Check your Firestore rules (see SETUP_CLOUD.md).")
            code !in 200..299 -> throw Exception("Restore failed ($code).")
            else -> JSONObject(text).getJSONObject("fields").getJSONObject("data").getString("stringValue")
        }
    }
}

object Backup {
    suspend fun export(db: AppDb, reasons: List<String>): String {
        val d = db.dao()
        val o = JSONObject().put("v", 1)
        o.put("habits", JSONArray().also { a -> d.allHabits().forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("category", it.category)
                .put("startTime", it.startTime).put("cost", it.costPerUnit).put("perDay", it.unitsPerDay)
                .put("unit", it.unitName).put("best", it.bestStreakMs).put("archived", it.archived))
        } })
        o.put("cravings", JSONArray().also { a -> d.allCravings().forEach {
            a.put(JSONObject().put("id", it.id).put("habitId", it.habitId).put("time", it.time)
                .put("intensity", it.intensity).put("trigger", it.trigger).put("note", it.note).put("resisted", it.resisted))
        } })
        o.put("relapses", JSONArray().also { a -> d.allRelapses().forEach {
            a.put(JSONObject().put("id", it.id).put("habitId", it.habitId).put("time", it.time)
                .put("streak", it.streakMs).put("trigger", it.trigger))
        } })
        o.put("reasons", JSONArray(reasons))
        return o.toString()
    }

    /** Replaces everything on this phone with the backup. Returns the saved reasons. */
    suspend fun restore(db: AppDb, json: String): List<String> {
        val o = JSONObject(json)
        val habits = o.getJSONArray("habits").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Habit(it.getLong("id"), it.getString("name"), it.getString("category"), it.getLong("startTime"),
                it.getDouble("cost"), it.getDouble("perDay"), it.getString("unit"), it.getLong("best"), it.getBoolean("archived"))
        } }
        val cravings = o.getJSONArray("cravings").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Craving(it.getLong("id"), it.getLong("habitId"), it.getLong("time"), it.getInt("intensity"),
                it.getString("trigger"), it.getString("note"), it.getBoolean("resisted"))
        } }
        val relapses = o.getJSONArray("relapses").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Relapse(it.getLong("id"), it.getLong("habitId"), it.getLong("time"), it.getLong("streak"), it.getString("trigger"))
        } }
        val d = db.dao()
        db.withTransaction {
            d.clearCravings(); d.clearRelapses(); d.clearHabits()
            d.putHabits(habits); d.putCravings(cravings); d.putRelapses(relapses)
        }
        val r = o.optJSONArray("reasons") ?: JSONArray()
        return (0 until r.length()).map { r.getString(it) }
    }
}

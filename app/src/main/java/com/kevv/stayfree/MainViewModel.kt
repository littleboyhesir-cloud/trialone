package com.kevv.stayfree

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    private val dao = db.dao()
    private val prefs = app.getSharedPreferences("cache", Context.MODE_PRIVATE)
    private val acct = app.getSharedPreferences("account", Context.MODE_PRIVATE)

    /** null = still loading from the database */
    val habits: StateFlow<List<Habit>?> = dao.habits()
        .map { it as List<Habit>? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val selectedId = MutableStateFlow<Long?>(prefs.getLong("selected", -1L).takeIf { it >= 0 })

    val selected: StateFlow<Habit?> = combine(habits, selectedId) { list, id ->
        list?.let { l -> l.firstOrNull { it.id == id } ?: l.firstOrNull() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val cravings: StateFlow<List<Craving>> = selected.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList<Craving>()) else dao.cravings(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val relapses: StateFlow<List<Relapse>> = selected.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList<Relapse>()) else dao.relapses(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val now: StateFlow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(1000) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    // ---- Internet data: quotes ----
    val quote = MutableStateFlow<Pair<String, String>?>(null)
    val quoteLoading = MutableStateFlow(false)
    val quoteOnline = MutableStateFlow(true)

    fun refreshQuote() {
        viewModelScope.launch {
            quoteLoading.value = true
            val q = Net.quote()
            quoteOnline.value = q != null
            quote.value = q ?: fallbackQuotes.random()
            quoteLoading.value = false
        }
    }

    // ---- Internet data: Wikipedia articles (cached for offline use) ----
    val wiki = MutableStateFlow<Map<String, WikiInfo?>>(emptyMap())
    val wikiLoading = MutableStateFlow(false)

    fun loadWiki(titles: List<String>, force: Boolean = false) {
        viewModelScope.launch {
            wikiLoading.value = true
            for (t in titles) {
                if (!force && wiki.value[t] != null) continue
                val fresh = Net.wiki(t)
                val info = fresh?.also { writeCache(t, it) } ?: readCache(t)
                wiki.value = wiki.value + (t to info)
            }
            wikiLoading.value = false
        }
    }

    private fun writeCache(t: String, i: WikiInfo) {
        val o = JSONObject().put("title", i.title).put("extract", i.extract).put("url", i.url ?: "")
        prefs.edit().putString("wiki_$t", o.toString()).apply()
    }

    private fun readCache(t: String): WikiInfo? = runCatching {
        val o = JSONObject(prefs.getString("wiki_$t", null) ?: return null)
        WikiInfo(o.getString("title"), o.getString("extract"), o.optString("url").ifBlank { null })
    }.getOrNull()

    // ---- Personal reasons ----
    val reasons = MutableStateFlow(loadReasons())

    private fun loadReasons(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("reasons", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveReasons() { prefs.edit().putString("reasons", JSONArray(reasons.value).toString()).apply() }

    fun addReason(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        reasons.value = reasons.value + t
        saveReasons(); scheduleBackup()
    }

    fun removeReason(i: Int) {
        reasons.value = reasons.value.filterIndexed { idx, _ -> idx != i }
        saveReasons(); scheduleBackup()
    }

    // ---- Notifications ----
    val notifHours = MutableStateFlow(
        prefs.getStringSet("notif_hours", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    )

    fun setNotif(hours: Set<Int>) {
        notifHours.value = hours
        prefs.edit().putStringSet("notif_hours", hours.map { it.toString() }.toSet()).apply()
        Reminders.apply(getApplication(), hours)
    }

    fun testNotification() {
        val h = selected.value ?: return
        val (t, x) = Messages.progress(h, 8, prefs)
        Notifier.post(getApplication(), 999, t, x)
    }

    // ---- Account + cloud backup ----
    val session = MutableStateFlow(Cloud.load(acct))
    val cloudStatus = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val lastBackup = MutableStateFlow(acct.getLong("lastBackup", 0))
    private var backupJob: Job? = null

    private fun scheduleBackup() {
        if (session.value == null || !Cloud.configured) return
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            delay(3000)
            val s = freshSession() ?: return@launch
            doBackup(s)
        }
    }

    private suspend fun freshSession(): Cloud.Session? {
        var s = session.value ?: return null
        if (System.currentTimeMillis() > s.expiresAt - 60_000) {
            val r = Cloud.refresh(s)
            if (r.isFailure) {
                cloudStatus.value = r.exceptionOrNull()?.message ?: "Please sign in again."
                if (r.exceptionOrNull()?.message?.startsWith("Session expired") == true) signOut()
                return null
            }
            s = r.getOrThrow()
            session.value = s
            Cloud.save(acct, s)
        }
        return s
    }

    private suspend fun doBackup(s: Cloud.Session): Boolean {
        val r = Cloud.upload(s, Backup.export(db, reasons.value))
        if (r.isSuccess) {
            val t = System.currentTimeMillis()
            lastBackup.value = t
            acct.edit().putLong("lastBackup", t).apply()
        } else {
            cloudStatus.value = r.exceptionOrNull()?.message ?: "Backup failed."
        }
        return r.isSuccess
    }

    private suspend fun restoreFrom(json: String) {
        reasons.value = Backup.restore(db, json)
        saveReasons()
        selectedId.value = null
        prefs.edit().remove("selected").apply()
    }

    fun signIn(email: String, pw: String, create: Boolean) {
        if (email.isBlank() || pw.isBlank()) { cloudStatus.value = "Enter your email and password."; return }
        viewModelScope.launch {
            busy.value = true
            cloudStatus.value = ""
            val r = if (create) Cloud.signUp(email, pw) else Cloud.signIn(email, pw)
            if (r.isFailure) {
                cloudStatus.value = r.exceptionOrNull()?.message ?: "Something went wrong."
            } else {
                val s = r.getOrThrow()
                session.value = s
                Cloud.save(acct, s)
                if (create) {
                    cloudStatus.value = if (doBackup(s)) "Account created. Your data is backed up." else cloudStatus.value
                } else {
                    val remote = Cloud.download(s)
                    val text = remote.getOrNull()
                    when {
                        remote.isFailure -> cloudStatus.value = remote.exceptionOrNull()?.message ?: "Couldn't check cloud data."
                        text == null -> { doBackup(s); cloudStatus.value = "Signed in. Your data is backed up." }
                        habits.value.isNullOrEmpty() -> { restoreFrom(text); cloudStatus.value = "Welcome back. Your data was restored." }
                        else -> cloudStatus.value = "Signed in. Tap Restore to load your cloud data, or Back up now to overwrite it with this phone's data."
                    }
                }
            }
            busy.value = false
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            busy.value = true
            val s = freshSession()
            if (s != null && doBackup(s)) cloudStatus.value = "Backup saved."
            busy.value = false
        }
    }

    fun restoreNow() {
        viewModelScope.launch {
            busy.value = true
            val s = freshSession()
            if (s != null) {
                val r = Cloud.download(s)
                if (r.isFailure) cloudStatus.value = r.exceptionOrNull()?.message ?: "Restore failed."
                else {
                    val text = r.getOrNull()
                    if (text == null) cloudStatus.value = "No cloud backup found yet."
                    else { restoreFrom(text); cloudStatus.value = "Restored from cloud." }
                }
            }
            busy.value = false
        }
    }

    fun resetPassword(email: String) {
        if (email.isBlank()) { cloudStatus.value = "Enter your email first."; return }
        viewModelScope.launch {
            busy.value = true
            val r = Cloud.resetPassword(email)
            cloudStatus.value = if (r.isSuccess) "Password reset email sent." else (r.exceptionOrNull()?.message ?: "Failed.")
            busy.value = false
        }
    }

    fun signOut() {
        session.value = null
        Cloud.clear(acct)
        cloudStatus.value = "Signed out."
    }

    // ---- Actions ----
    fun select(id: Long) {
        selectedId.value = id
        prefs.edit().putLong("selected", id).apply()
    }

    fun addHabit(name: String, category: String, startTime: Long, cost: Double, perDay: Double, unit: String) {
        viewModelScope.launch {
            val id = dao.insertHabit(Habit(name = name, category = category, startTime = startTime,
                costPerUnit = cost, unitsPerDay = perDay, unitName = unit))
            select(id)
            scheduleBackup()
        }
    }

    fun logCraving(intensity: Int, trigger: String, note: String, resisted: Boolean) {
        val h = selected.value ?: return
        viewModelScope.launch {
            dao.insertCraving(Craving(habitId = h.id, time = System.currentTimeMillis(),
                intensity = intensity, trigger = trigger, note = note, resisted = resisted))
            scheduleBackup()
        }
    }

    fun deleteCraving(c: Craving) { viewModelScope.launch { dao.deleteCraving(c); scheduleBackup() } }

    fun slip(trigger: String) {
        val h = selected.value ?: return
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            val streak = (t - h.startTime).coerceAtLeast(0)
            dao.insertRelapse(Relapse(habitId = h.id, time = t, streakMs = streak, trigger = trigger))
            dao.updateHabit(h.copy(startTime = t, bestStreakMs = maxOf(h.bestStreakMs, streak)))
            scheduleBackup()
        }
    }

    fun archive() {
        val h = selected.value ?: return
        viewModelScope.launch { dao.archiveHabit(h.id); scheduleBackup() }
    }

    init { refreshQuote() }
}

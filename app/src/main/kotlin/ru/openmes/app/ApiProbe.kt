package ru.openmes.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.openmes.core.data.TokenStore
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * ВРЕМЕННЫЙ пробник API (удалить после исследования).
 *
 * Читает files/probe.txt (external), выполняет запросы без authenticator/кэша
 * (401 не разлогинит) и пишет ответы в logcat с тегом OMProbe.
 * Формат строки: `GET url | Header: value | BODY {json} | SAVE var=json_key`.
 * Подстановки: {student_id} {person_guid} {profile_id} {role_id} {mesh_token} {today} {from} {to} и сохранённые {var}.
 */
object ApiProbe {
    private const val TAG = "OMProbe"

    fun runIfRequested(context: Context, tokenStore: TokenStore) {
        val file = File(context.getExternalFilesDir(null), "probe.txt")
        if (!file.exists()) return
        CoroutineScope(Dispatchers.IO).launch {
            delay(3000)
            runCatching { run(file.readLines(), tokenStore) }.onFailure { Log.e(TAG, "probe failed", it) }
            Log.i(TAG, "=== DONE ===")
        }
    }

    private fun run(lines: List<String>, tokenStore: TokenStore) {
        val tokens = tokenStore.load() ?: return Log.w(TAG, "no session").let { }
        val today = LocalDate.now()
        val vars = mutableMapOf(
            "student_id" to tokens.studentId.orEmpty(),
            "person_guid" to tokens.personGuid.orEmpty(),
            "profile_id" to tokens.profileId.orEmpty(),
            "role_id" to tokens.roleId.orEmpty(),
            "mesh_token" to tokens.meshAccessToken.orEmpty(),
            "sudir_token" to tokens.sudirAccessToken.orEmpty(),
            "today" to today.toString(),
            "from" to today.minusDays(30).toString(),
            "to" to today.plusDays(30).toString(),
        )
        Log.i(TAG, "ids student=${vars["student_id"]} guid=${vars["person_guid"]} profile=${vars["profile_id"]} role=${vars["role_id"]}")
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        fun sub(s: String) = vars.entries.fold(s) { acc, (k, v) -> acc.replace("{$k}", v) }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(" | ").map { it.trim() }
            val (method, url) = parts[0].split(" ", limit = 2).let { it[0] to sub(it[1]) }
            val builder = Request.Builder().url(url)
                .header("auth-token", tokens.meshAccessToken.orEmpty())
                .header("Authorization", "Bearer ${tokens.meshAccessToken.orEmpty()}")
                .header("X-Mes-Subsystem", "familypom")
                .header("Accept", "application/json")
            var body: String? = null
            val saves = mutableListOf<Pair<String, String>>()
            for (p in parts.drop(1)) {
                when {
                    p.startsWith("BODY ") -> body = sub(p.removePrefix("BODY "))
                    p.startsWith("SAVE ") -> p.removePrefix("SAVE ").split("=", limit = 2).let { saves += it[0] to it[1] }
                    p.startsWith("-") -> builder.removeHeader(p.removePrefix("-"))
                    ":" in p -> p.split(":", limit = 2).let { builder.header(it[0].trim(), sub(it[1].trim())) }
                }
            }
            builder.method(method, body?.toRequestBody("application/json".toMediaType()))
            val result = runCatching {
                client.newCall(builder.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    for ((name, key) in saves) {
                        Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)").find(text)?.let { vars[name] = it.groupValues[1] }
                    }
                    "${r.code} ${r.header("Location")?.let { "-> $it " }.orEmpty()}len=${text.length}\n$text"
                }
            }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message}" }
            Log.i(TAG, ">>> $method $url")
            result.take(12000).chunked(3500).forEach { Log.i(TAG, it) }
        }
    }
}

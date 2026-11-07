package com.friday.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Cmd(
    val reply: String,
    val action: String = "none",
    val name: String = "",
    val text: String = "",
    val query: String = "",
    val app: String = ""
)

object Brain {
    private val models = listOf("claude-haiku-5-5", "claude-sonnet-5-5")
    private val history = ArrayList<Pair<String, String>>()

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        return """
Tum FRIDAY ho: user ka Jarvis jaisa personal AI assistant aur ek sacha dost.
Rules:
- Hinglish (Roman script) me bolo, user ko 'boss' ya 'yaar' bolo. Reply chhota rakho (1-3 vaakya) kyunki wo bolke sunaya jayega. Koi emoji, markdown ya list mat use karo.
- Dost ki tarah dil se salah do: honest raho, zarurat ho to pyar se tok do, hausla badhao. Health, paisa ya kanoon ka serious mamla ho to expert se milne ki salah bhi do.
- Agar user phone control karwana chahe to action chuno, warna action "none".
- Output SIRF ek JSON object, aur kuch nahi:
{"reply":"...","action":"...","name":"...","text":"...","query":"...","app":"..."}
Actions: none, call, whatsapp, youtube, open_app, unlock, lock, flashlight_on, flashlight_off, volume_up, volume_down, home, back.
- call: name = contact ka naam ya number.
- whatsapp: name = contact ka naam ya number, text = bheja jane wala poora message (user ki bhasha me, natural).
- youtube: query = kya search ya play karna hai.
- open_app: app = app ka naam.
- reply me wo bolo jo Friday kahega, jaise "Ok boss, Rahul ko message bhej raha hoon".
Abhi ka samay: """.trimIndent() + " " + now
    }

    fun ask(ctx: Context, user: String): Cmd {
        val key = ctx.getSharedPreferences("friday", Context.MODE_PRIVATE)
            .getString("key", "")?.trim() ?: ""
        if (key.isEmpty()) return local(user, "API key set nahi hai.")
        if (!key.startsWith("sk-")) {
            return try {
                askGemini(key, user)
            } catch (e: Exception) {
                local(user, "Internet nahi chal raha boss.")
            }
        }
        try {
            val msgs = JSONArray()
            history.takeLast(8).forEach { (u, a) ->
                msgs.put(JSONObject().put("role", "user").put("content", u))
                msgs.put(JSONObject().put("role", "assistant").put("content", a))
            }
            msgs.put(JSONObject().put("role", "user").put("content", user))

            for (m in models) {
                val body = JSONObject()
                    .put("model", m)
                    .put("max_tokens", 400)
                    .put("system", system())
                    .put("messages", msgs)
                val (code, txt) = post(key, body)
                if (code in 200..299) {
                    val raw = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text")
                    history.add(Pair(user, raw))
                    if (history.size > 20) history.removeAt(0)
                    return parse(raw)
                }
                if (code == 401) return Cmd("API key galat hai boss. App me check karo.")
            }
            return local(user, "AI se jawab nahi mila.")
        } catch (e: Exception) {
            return local(user, "Internet nahi chal raha boss.")
        }
    }

    // ---------- Gemini (free key: aistudio.google.com) ----------
    private fun askGemini(key: String, user: String): Cmd {
        val contents = JSONArray()
        history.takeLast(8).forEach { (u, a) ->
            contents.put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", u)))
            )
            contents.put(
                JSONObject().put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", a)))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))
        )
        for (m in listOf("gemini-2.5-flash", "gemini-flash-latest")) {
            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system())))
                )
                .put("contents", contents)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("maxOutputTokens", 1024)
                        .put("responseMimeType", "application/json")
                        .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                )
            val (code, txt) = postGemini(key, m, body)
            if (code in 200..299) {
                val raw = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                history.add(Pair(user, raw))
                if (history.size > 20) history.removeAt(0)
                return parse(raw)
            }
            if (code == 429) return Cmd("Free limit abhi khatam hai boss, thodi der baad try karo.")
            if (code == 401 || code == 403 || (code == 400 && txt.contains("API key not valid"))) {
                return Cmd("API key galat hai boss. App me check karo.")
            }
        }
        return local(user, "AI se jawab nahi mila.")
    }

    private fun postGemini(key: String, model: String, body: JSONObject): Pair<Int, String> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("x-goog-api-key", key)
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return Pair(code, txt)
    }

    private fun post(key: String, body: JSONObject): Pair<Int, String> {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return Pair(code, txt)
    }

    private fun parse(raw: String): Cmd {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try {
                val j = JSONObject(raw.substring(s, e + 1))
                return Cmd(
                    j.optString("reply"), j.optString("action", "none"),
                    j.optString("name"), j.optString("text"),
                    j.optString("query"), j.optString("app")
                )
            } catch (x: Exception) {
            }
        }
        return Cmd(raw)
    }

    // Internet / API key na ho to basic commands
    private fun local(t: String, note: String = ""): Cmd {
        val c = t.lowercase(Locale.ROOT).trim()
        return when {
            c.startsWith("open ") -> Cmd("Ok boss", "open_app", app = c.removePrefix("open ").trim())
            c.endsWith(" kholo") -> Cmd("Ok boss", "open_app", app = c.removeSuffix(" kholo").trim())
            c.startsWith("call ") -> Cmd("Call laga raha hoon", "call", name = c.removePrefix("call ").trim())
            c.startsWith("youtube ") -> Cmd("YouTube chala raha hoon", "youtube", query = c.removePrefix("youtube ").trim())
            c.startsWith("play ") -> Cmd("YouTube chala raha hoon", "youtube", query = c.removePrefix("play ").trim())
            c.contains("unlock") -> Cmd("Unlock kar raha hoon", "unlock")
            c.contains("torch on") || c.contains("flashlight on") -> Cmd("Torch on", "flashlight_on")
            c.contains("torch off") || c.contains("flashlight off") -> Cmd("Torch off", "flashlight_off")
            c.contains("volume up") -> Cmd("Volume badha raha hoon", "volume_up")
            c.contains("volume down") -> Cmd("Volume kam kar raha hoon", "volume_down")
            c.contains("time") || c.contains("kitne baje") ->
                Cmd("Abhi " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()) + " hue hain")
            else -> Cmd((if (note.isNotEmpty()) "$note " else "") + "Abhi sirf basic commands chalenge.")
        }
    }
}

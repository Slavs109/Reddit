package com.slavs109.redditgallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

object RedditAuth {
    private const val PREFS = "reddit_auth"
    private const val REDIRECT = "redditgallery://oauth"
    private const val AGENT = "android:com.slavs109.redditgallery:v0.3 (by /u/Slavs109)"
    // Register an installed-app OAuth client with Reddit. Never embed a client secret.
    fun clientId(context: Context) = context.getSharedPreferences(PREFS, 0).getString("client_id", "") ?: ""
    fun saveClientId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("client_id", id.trim()).apply()
    }
    fun token(context: Context) = context.getSharedPreferences(PREFS, 0).getString("access_token", null)
    fun signedIn(context: Context) = !token(context).isNullOrEmpty()
    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }
    private fun randomString(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    fun login(context: Context): Intent {
        require(clientId(context).isNotBlank()) { "Enter your Reddit OAuth client ID in Settings first." }
        val state = randomString()
        val verifier = randomString()
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        context.getSharedPreferences(PREFS, 0).edit().putString("oauth_state", state).putString("pkce_verifier", verifier).apply()
        val uri = Uri.parse("https://www.reddit.com/api/v1/authorize.compact").buildUpon()
            .appendQueryParameter("client_id", clientId(context))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("duration", "permanent")
            .appendQueryParameter("scope", "read identity mysubreddits")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }
    fun exchange(context: Context, uri: Uri): Result<Unit> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, 0)
        require(uri.getQueryParameter("state") == prefs.getString("oauth_state", null)) { "Login state mismatch." }
        val code = uri.getQueryParameter("code") ?: error(uri.getQueryParameter("error") ?: "Missing authorization code")
        val verifier = prefs.getString("pkce_verifier", null) ?: error("Missing login verifier")
        val data = "grant_type=authorization_code&code=${Uri.encode(code)}&redirect_uri=${Uri.encode(REDIRECT)}&code_verifier=${Uri.encode(verifier)}"
        val response = postToken(context, data)
        prefs.edit().putString("access_token", response.getString("access_token"))
            .putString("refresh_token", response.optString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000)
            .remove("oauth_state").remove("pkce_verifier").apply()
    }
    private fun postToken(context: Context, body: String): JSONObject {
        val conn = URL("https://www.reddit.com/api/v1/access_token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        val basic = Base64.encodeToString("${clientId(context)}:".toByteArray(), Base64.NO_WRAP)
        conn.setRequestProperty("Authorization", "Basic $basic")
        conn.setRequestProperty("User-Agent", AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val response = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
            if (conn.responseCode !in 200..299) error("Reddit OAuth HTTP ${conn.responseCode}: $response")
            JSONObject(response).also { if (it.has("error")) error(it.getString("error")) }
        } finally { conn.disconnect() }
    }
    fun validToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val existing = token(context) ?: error("Log in to Reddit in Settings.")
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 60000) return existing
        val refresh = prefs.getString("refresh_token", null)?.takeIf { it.isNotBlank() } ?: error("Session expired. Log in again.")
        val json = postToken(context, "grant_type=refresh_token&refresh_token=${Uri.encode(refresh)}")
        val updated = json.getString("access_token")
        prefs.edit().putString("access_token", updated).putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000).apply()
        return updated
    }
    fun userAgent() = AGENT
}

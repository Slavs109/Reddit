package com.slavs109.redditgallery

import android.os.Bundle
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MediaPost(val id: String, val subreddit: String, val title: String, val image: String, val isVideo: Boolean, val url: String)

private suspend fun loadFeed(context: android.content.Context, feeds: List<String>): Result<List<MediaPost>> = withContext(Dispatchers.IO) {
    runCatching {
        val accessToken = RedditAuth.validToken(context)
        feeds.flatMap { subreddit ->
            val connection = (URL("https://oauth.reddit.com/r/${subreddit}/hot.json?limit=50&raw_json=1").openConnection() as HttpURLConnection)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", RedditAuth.userAgent())
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("Reddit returned HTTP $code for r/$subreddit. Public feed access may be restricted.")
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val children = root.getJSONObject("data").getJSONArray("children")
                buildList {
                    for (i in 0 until children.length()) {
                        val p = children.getJSONObject(i).getJSONObject("data")
                        if (p.optBoolean("over_18")) continue
                        val video = p.optBoolean("is_video")
                        val gallery = p.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
                        val preview = gallery?.optJSONObject("source")?.optString("url")?.replace("&amp;", "&")
                        val direct = p.optString("url_overridden_by_dest")
                        val image = when {
                            !preview.isNullOrBlank() -> preview
                            direct.matches(Regex("https://.*\\.(jpg|jpeg|png|webp)(\\?.*)?", RegexOption.IGNORE_CASE)) -> direct
                            else -> ""
                        }
                        // Inspired by MojiRS's gallery repair approach: use Reddit's
                        // gallery_data/media_metadata rather than discarding gallery posts.
                        val galleryItems = p.optJSONObject("gallery_data")?.optJSONArray("items")
                        val metadata = p.optJSONObject("media_metadata")
                        if (galleryItems != null && metadata != null) {
                            for (index in 0 until galleryItems.length()) {
                                val mediaId = galleryItems.optJSONObject(index)?.optString("media_id") ?: continue
                                val media = metadata.optJSONObject(mediaId) ?: continue
                                val source = media.optJSONObject("s")?.optString("u")?.replace("&amp;", "&")
                                val previews = media.optJSONArray("p")
                                val fallback = if (previews != null && previews.length() > 0)
                                    previews.optJSONObject(previews.length() - 1)?.optString("u")?.replace("&amp;", "&") else null
                                val url = source?.takeIf { it.startsWith("https://") }
                                    ?: fallback?.takeIf { it.startsWith("https://") }
                                    ?: "https://i.redd.it/$mediaId"
                                add(MediaPost("${p.optString("id")}_$index", subreddit, p.optString("title"), url, false, p.optString("permalink")))
                            }
                        } else if (image.isNotBlank()) {
                            add(MediaPost(p.optString("id"), subreddit, p.optString("title"), image, video, p.optString("permalink")))
                        }
                    }
                }
            } finally { connection.disconnect() }
        }.distinctBy { it.id }
    }
}

class MainActivity : ComponentActivity() {
    private val loginResult = mutableStateOf<String?>(null)
    private val authVersion = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
        setContent { GalleryApp(loginResult.value, authVersion.intValue) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }
    private fun handleCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "redditgallery" || uri.host != "oauth") return
        Thread {
            val result = RedditAuth.exchange(this, uri)
            runOnUiThread {
                loginResult.value = result.exceptionOrNull()?.message ?: "Logged in to Reddit."
                authVersion.intValue++
            }
        }.start()
    }
}

@Composable
fun GalleryApp(loginMessage: String?, authVersion: Int) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(RedditAuth.clientId(context)) }
    var loggedIn by remember(authVersion) { mutableStateOf(RedditAuth.signedIn(context)) }
    var authError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("Gallery") }
    var filter by remember { mutableStateOf("All") }
    var input by remember { mutableStateOf("") }
    val feeds = remember { mutableStateListOf("pics", "EarthPorn") }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var posts by remember { mutableStateOf(emptyList<MediaPost>()) }
    var selected by remember { mutableStateOf<MediaPost?>(null) }
    LaunchedEffect(feeds.toList(), reload, authVersion, loggedIn) {
        if (!loggedIn) { posts = emptyList(); error = null; loading = false; return@LaunchedEffect }
        loading = true
        error = null
        val result = loadFeed(context, feeds.toList())
        result.onSuccess { posts = it }.onFailure { error = it.message ?: "Could not load Reddit feed"; posts = emptyList() }
        loading = false
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = { Column {
            Text("Reddit Gallery", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
            Row { listOf("Gallery", "Feeds", "Favorites", "Settings").forEach { name ->
                TextButton(onClick = { tab = name; selected = null }) { Text(name) }
            } }
        } }) { padding ->
            Column(Modifier.padding(padding).padding(12.dp)) {
                if (selected != null) {
                    TextButton(onClick = { selected = null }) { Text("Back to gallery") }
                    AsyncImage(model = selected!!.image, contentDescription = selected!!.title, modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                    Text(selected!!.title)
                } else when (tab) {
                    "Gallery" -> {
                        Row { listOf("All", "Photos", "Videos").forEach { name ->
                            FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) }, modifier = Modifier.padding(end = 6.dp))
                        } }
                        Row { Text("Combined gallery", Modifier.weight(1f)); TextButton(onClick = { reload++ }) { Text("Refresh") } }
                        if (!loggedIn) Text("Log in through Settings to load Reddit posts.")
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                        val visible = posts.filter { filter == "All" || (filter == "Videos") == it.isVideo }
                        if (!loading && visible.isEmpty() && error == null) Text("No matching media. Try adding a different subreddit.")
                        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(visible, key = { it.id }) { post ->
                                Card(shape = RoundedCornerShape(6.dp), modifier = Modifier.aspectRatio(0.75f).clickable { selected = post }) {
                                    AsyncImage(model = post.image, contentDescription = post.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                        }
                    }
                    "Feeds" -> {
                        OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Subreddit name") })
                        Button(onClick = {
                            val value = input.trim().removePrefix("r/")
                            if (value.matches(Regex("[A-Za-z0-9_]{2,21}")) && feeds.none { it.equals(value, true) }) feeds.add(value)
                            input = ""
                        }) { Text("Add feed") }
                        feeds.toList().forEach { name -> Row { Text("r/$name", Modifier.weight(1f)); TextButton(onClick = { feeds.remove(name) }) { Text("Remove") } } }
                    }
                    "Favorites" -> Text("Local favorites are coming next.")
                    else -> {
                        Text("Reddit account", style = MaterialTheme.typography.titleLarge)
                        Text("Requires a Reddit-approved installed-app OAuth client ID. Redirect URI: redditgallery://oauth")
                        OutlinedTextField(value = clientId, onValueChange = { clientId = it }, label = { Text("OAuth client ID") })
                        Button(onClick = { RedditAuth.saveClientId(context, clientId); authError = null }) { Text("Save client ID") }
                        if (loggedIn) {
                            Text("Connected to Reddit")
                            Button(onClick = { RedditAuth.logout(context); loggedIn = false }) { Text("Log out") }
                        } else {
                            Button(onClick = {
                                RedditAuth.saveClientId(context, clientId)
                                runCatching { context.startActivity(RedditAuth.login(context)) }
                                    .onFailure { authError = it.message }
                            }) { Text("Login with Reddit") }
                        }
                        if (loginMessage != null) Text(loginMessage)
                        if (authError != null) Text(authError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

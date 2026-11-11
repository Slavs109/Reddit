package com.slavs109.redditgallery

import android.os.Bundle
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

private suspend fun loadFeed(feeds: List<String>): Result<List<MediaPost>> = withContext(Dispatchers.IO) {
    runCatching {
        feeds.flatMap { subreddit ->
            val connection = (URL("https://www.reddit.com/r/${subreddit}/hot.json?limit=50&raw_json=1").openConnection() as HttpURLConnection)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "android:com.slavs109.redditgallery:v0.2 (public media browser)")
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
                        if (image.isBlank()) continue
                        add(MediaPost(p.optString("id"), subreddit, p.optString("title"), image, video, p.optString("permalink")))
                    }
                }
            } finally { connection.disconnect() }
        }.distinctBy { it.id }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GalleryApp() }
    }
}

@Composable
fun GalleryApp() {
    var tab by remember { mutableStateOf("Gallery") }
    var filter by remember { mutableStateOf("All") }
    var input by remember { mutableStateOf("") }
    val feeds = remember { mutableStateListOf("pics", "EarthPorn") }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var posts by remember { mutableStateOf(emptyList<MediaPost>()) }
    var selected by remember { mutableStateOf<MediaPost?>(null) }
    LaunchedEffect(feeds.toList(), reload) {
        loading = true
        error = null
        val result = loadFeed(feeds.toList())
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
                    else -> Text("Autoplay, sorting and grid size controls are coming next.")
                }
            }
        }
    }
}

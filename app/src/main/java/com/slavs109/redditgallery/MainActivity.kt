package com.slavs109.redditgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    var subreddit by remember { mutableStateOf("") }
    val feeds = remember { mutableStateListOf<String>() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = { Column { Text("Reddit Gallery", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
                Row { listOf("Gallery", "Feeds", "Favorites", "Settings").forEach { name ->
                    TextButton(onClick = { tab = name }) { Text(name) }
                } }
            } }
        ) { padding ->
            Column(Modifier.padding(padding).padding(12.dp)) {
                when (tab) {
                    "Gallery" -> {
                        Row { listOf("All", "Photos", "Videos", "GIFs").forEach { name ->
                            FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) }, modifier = Modifier.padding(end = 4.dp))
                        } }
                        Text("Combined gallery • $filter", modifier = Modifier.padding(vertical = 12.dp))
                        LazyVerticalGrid(columns = GridCells.Fixed(3)) { items(feeds) { Text("r/$it", modifier = Modifier.padding(8.dp)) } }
                        if (feeds.isEmpty()) Text("Add subreddits under Feeds to get started.")
                        Text("Media loading will be added after Reddit API access is configured.")
                    }
                    "Feeds" -> {
                        OutlinedTextField(value = subreddit, onValueChange = { subreddit = it }, label = { Text("Subreddit name") })
                        Button(onClick = { val value = subreddit.trim().removePrefix("r/"); if (value.isNotEmpty() && value !in feeds) feeds.add(value); subreddit = "" }) { Text("Add feed") }
                        feeds.forEach { name -> Row { Text("r/$name", modifier = Modifier.weight(1f)); TextButton(onClick = { feeds.remove(name) }) { Text("Remove") } } }
                    }
                    "Favorites" -> Text("Your locally saved posts will appear here.")
                    else -> Text("Settings: autoplay, grid size and sorting are planned.")
                }
            }
        }
    }
}

package com.whapea.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WhapeaTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF4D8DFF), onPrimary = Color(0xFF06142E),
            primaryContainer = Color(0xFF17356B), onPrimaryContainer = Color(0xFFE8EEF8),
            background = Color(0xFF0A111B), surface = Color(0xFF18222F)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1560FF), onPrimary = Color.White,
            primaryContainer = Color(0xFFD9E6FF), onPrimaryContainer = Color(0xFF0E1A2B),
            background = Color(0xFFF3F6FA), surface = Color.White
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun hhmm(ts: Long) = timeFmt.format(Date(ts))

@Composable
fun Avatar(name: String, size: Int = 48) {
    val hue = (name.hashCode() and 0x7fffffff) % 360
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.hsv(hue.toFloat(), 0.55f, 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1).uppercase(), color = Color.White, fontSize = (size / 2.4).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun App(startNearby: () -> Unit, makeVisible: () -> Unit) {
    if (Core.myName.isBlank()) {
        Onboarding()
        return
    }
    val open = Core.openPeer
    if (open != null) {
        BackHandler { Core.openPeer = null }
        ChatScreen(open)
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { if (!Core.running) startNearby() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("💬") }, label = { Text("Chats") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("📡") }, label = { Text("Nearby") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙️") }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatsTab(onFind = { tab = 1 })
                1 -> NearbyTab(startNearby, makeVisible)
                else -> SettingsTab(startNearby)
            }
        }
    }
}

@Composable
fun Onboarding() {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Whapea", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Chat with people near you over Bluetooth. No internet, no phone number.", fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name, onValueChange = { if (it.length <= 24) name = it },
            label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { Core.setName(name) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue") }
    }
}

@Composable
fun Header(title: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ChatsTab(onFind: () -> Unit) {
    val names = (Core.messages.map { it.peer } + Core.connected.keys).distinct()
        .sortedByDescending { n -> Core.messages.lastOrNull { it.peer == n }?.ts ?: Long.MAX_VALUE }
    Column(Modifier.fillMaxSize()) {
        Header("Whapea", Core.statusText)
        if (names.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No chats yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Open Nearby on both phones and tap Connect to start chatting.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onFind) { Text("Find people nearby") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(names) { n ->
                    val last = Core.messages.lastOrNull { it.peer == n }
                    val online = Core.connected.containsKey(n)
                    val unread = Core.unread[n] ?: 0
                    Row(
                        Modifier.fillMaxWidth().clickable { Core.openPeer = n; Core.unread.remove(n) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(Core.nameOf(n))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(Core.nameOf(n), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                last?.text ?: if (online) "In range. Say hello" else "Out of range",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (last != null) Text(hhmm(last.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (unread > 0) {
                                Box(
                                    Modifier.padding(top = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).size(22.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text("$unread", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
                            } else if (online) {
                                Text("● in range", fontSize = 11.sp, color = Color(0xFF12A15A))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NearbyTab(startNearby: () -> Unit, makeVisible: () -> Unit) {
    val list = Core.found.filter { f -> !Core.connected.containsKey(f.address) }
    Column(Modifier.fillMaxSize()) {
        Header("Nearby", Core.statusText)
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!Core.running) {
                Button(onClick = startNearby) { Text("Start") }
            } else {
                Button(onClick = { Core.scan() }) { Text(if (Core.scanning) "Scanning..." else "Scan again") }
                OutlinedButton(onClick = makeVisible) { Text("Make me visible") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (Core.connected.isNotEmpty()) {
                item { Text("Connected", Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp), fontWeight = FontWeight.SemiBold) }
                items(Core.connected.entries.toList()) { e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(e.value, 40)
                        Text(e.value, Modifier.weight(1f).padding(start = 12.dp), fontSize = 16.sp)
                        TextButton(onClick = { Core.openPeer = e.key }) { Text("Chat") }
                    }
                }
            }
            item { Text("Phones found", Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp), fontWeight = FontWeight.SemiBold) }
            item {
                Text(
                    "Tip: pair both phones once in Android Bluetooth settings. Paired phones appear here without needing to be visible. Whapea must be open on the other phone.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(list) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(f.name, 40)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(f.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (f.paired) "Paired" else "Nearby", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { Core.connect(f) }) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(startNearby: () -> Unit) {
    var name by remember { mutableStateOf(Core.myName) }
    Column(Modifier.fillMaxSize()) {
        Header("Settings")
        Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { if (it.length <= 24) name = it },
                label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { Core.setName(name) }, enabled = name.isNotBlank() && name != Core.myName) { Text("Save name") }
            Text(
                "Restart scanning after changing your name so others see the new one.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Bluetooth", fontWeight = FontWeight.SemiBold)
                    Text(Core.statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (Core.running) {
                        OutlinedButton(onClick = { Core.stop() }) { Text("Stop scanning") }
                    } else {
                        Button(onClick = startNearby) { Text("Start scanning") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Whapea needs no internet, no phone number and no account. Messages go straight from phone to phone. " +
                    "Chats stay on this phone. Keep the app open while chatting. Version 1.0",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ChatScreen(peer: String) {
    val msgs = Core.messages.filter { it.peer == peer }
    val online = Core.connected.containsKey(peer)
    val state = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(msgs.size) {
        Core.unread.remove(peer)
        if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Core.openPeer = null }) { Text("←", fontSize = 22.sp) }
                Avatar(Core.nameOf(peer), 38)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(Core.nameOf(peer), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (online) "In range" else "Out of range. Messages will wait",
                        fontSize = 12.sp, color = if (online) Color(0xFF12A15A) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            item {
                Text(
                    "Messages are sent straight from phone to phone over Bluetooth.",
                    Modifier.fillMaxWidth().padding(16.dp), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(msgs, key = { it.id }) { m -> Bubble(m) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 4,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = { Core.sendText(peer, text); text = "" },
                enabled = text.isNotBlank()
            ) { Text("Send") }
        }
    }
}

@Composable
fun Bubble(m: Msg) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(m.text, fontSize = 16.sp)
                val tick = if (!m.mine) "" else when (m.status) {
                    0 -> "  🕓"
                    1 -> "  ✓"
                    else -> "  ✓✓"
                }
                Text(
                    hhmm(m.ts) + tick, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

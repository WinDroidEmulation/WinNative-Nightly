package com.winlator.cmod

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winlator.cmod.steam.service.SteamService
import com.winlator.cmod.steam.utils.PrefManager
import com.winlator.cmod.steam.utils.getAvatarURL
import com.winlator.cmod.steam.SteamLoginActivity
import com.winlator.cmod.steam.data.SteamApp
import com.winlator.cmod.steam.data.DepotInfo
import com.winlator.cmod.steam.data.DownloadInfo
import com.winlator.cmod.steam.enums.DownloadPhase
import com.winlator.cmod.db.PluviaDatabase
import com.winlator.cmod.utils.StorageUtils
import com.winlator.cmod.service.DownloadService
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.dragonbra.javasteam.enums.EPersonaState
import kotlin.math.abs

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.style.TextAlign

// ─── Color palette ───────────────────────────────────────────────────
private val BgDark = Color(0xFF0D1117)
private val SurfaceDark = Color(0xFF161B22)
private val CardDark = Color(0xFF1B2838)
private val Accent = Color(0xFF58A6FF)
private val AccentGlow = Color(0xFF1A9FFF)
private val TextPrimary = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B949E)
private val StatusOnline = Color(0xFF57CBDE)
private val StatusAway = Color(0xFFF0C040)
private val StatusOffline = Color(0xFF6E7681)

@AndroidEntryPoint
class UnifiedActivity : ComponentActivity() {
    @Inject lateinit var db: PluviaDatabase

    // Track the currently selected game in the carousel for Game Settings button
    private var selectedSteamAppId: Int = 0
    private var selectedSteamAppName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent,
                background = BgDark,
                surface = SurfaceDark,
                onSurface = TextPrimary
            )) {
                UnifiedHub()
            }
        }
    }

    // ─── Tab definitions ──────────────────────────────────────────────
    private data class TabDef(val label: String, val key: String)

    private fun buildTabs(aio: Boolean, storeVisible: Map<String, Boolean>): List<TabDef> {
        val base = mutableListOf(TabDef("Library", "library"), TabDef("Downloads", "downloads"))
        if (aio) {
            base.add(TabDef("Store", "store"))
        } else {
            if (storeVisible["steam"] != false) base.add(TabDef("Steam", "steam"))
            if (storeVisible["epic"] != false) base.add(TabDef("Epic", "epic"))
            if (storeVisible["gog"] != false) base.add(TabDef("GOG", "gog"))
            if (storeVisible["amazon"] != false) base.add(TabDef("Amazon", "amazon"))
        }
        return base
    }

    // ─── Main scaffold ────────────────────────────────────────────────
    @Composable
    fun UnifiedHub() {
        var aioMode by remember { mutableStateOf(PrefManager.aioStoreMode) }
        val storeVisible = remember { mutableStateMapOf("steam" to true, "epic" to true, "gog" to true, "amazon" to true) }
        val contentFilters = remember { mutableStateMapOf("games" to true, "dlc" to false, "applications" to false, "tools" to false) }
        val tabs = remember(aioMode, storeVisible.toMap()) { buildTabs(aioMode, storeVisible) }
        var selectedIdx by remember { mutableIntStateOf(0) }
        var showFilter by remember { mutableStateOf(false) }
        val isLoggedIn by SteamService.isLoggedInFlow.collectAsState()
        val steamApps by db.steamAppDao().getAllOwnedApps().collectAsState(initial = emptyList())
        val context = LocalContext.current
        val persona by SteamService.instance?.localPersona?.collectAsState()
            ?: remember { mutableStateOf(null) }
        val scope = rememberCoroutineScope()

        // Clamp selectedIdx if tabs shrink
        LaunchedEffect(tabs.size) { if (selectedIdx >= tabs.size) selectedIdx = 0 }
        LaunchedEffect(Unit) { SteamService.requestUserPersona() }

        Scaffold(
            containerColor = BgDark,
            topBar = { TopBar(tabs, selectedIdx, { selectedIdx = it }, persona, context, scope) }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().background(BgDark)) {
                val key = tabs.getOrNull(selectedIdx)?.key ?: "library"
                when (key) {
                    "library" -> LibraryCarousel(isLoggedIn, steamApps)
                    "downloads" -> DownloadsTab()
                    "steam", "store" -> SteamStoreTab(isLoggedIn, steamApps)
                    "epic" -> StorePlaceholderTab("Epic Games")
                    "gog" -> StorePlaceholderTab("GOG")
                    "amazon" -> StorePlaceholderTab("Amazon Games")
                }

                // ── Bottom-left filter button ──
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .shadow(8.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.5f))
                        .clip(CircleShape)
                        .background(SurfaceDark)
                        .clickable { showFilter = !showFilter },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = TextPrimary, modifier = Modifier.size(24.dp))
                }

                // ── Filter panel ──
                Box(modifier = Modifier.align(Alignment.BottomStart)) {
                    FilterPanel(
                        visible = showFilter,
                        onDismiss = { showFilter = false },
                        aioMode = aioMode,
                        onAioToggle = { aioMode = it; PrefManager.aioStoreMode = it },
                        storeVisible = storeVisible,
                        contentFilters = contentFilters
                    )
                }
            }
        }
    }

    // ─── Top bar ──────────────────────────────────────────────────────
    @Composable
    private fun TopBar(
        tabs: List<TabDef>,
        selectedIdx: Int,
        onSelect: (Int) -> Unit,
        persona: com.winlator.cmod.steam.data.SteamFriend?,
        context: android.content.Context,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        var showStatusMenu by remember { mutableStateOf(false) }
        val currentState = persona?.state ?: EPersonaState.Online

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Left: Settings button with circle shadow ──
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(6.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(CircleShape)
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    val intent = Intent(context, MainActivity::class.java)
                    intent.putExtra("return_to_unified", true)
                    context.startActivity(intent)
                }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Menu", tint = TextPrimary, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.width(8.dp))

            // ── Center: Adaptive tab bar with shadow pill ──
            Box(
                modifier = Modifier
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .height(44.dp)
                        .shadow(8.dp, RoundedCornerShape(24.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                        .clip(RoundedCornerShape(24.dp))
                        .background(SurfaceDark.copy(alpha = 0.85f))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = selectedIdx == index
                        val scale by animateFloatAsState(
                            if (selected) 1.05f else 1f,
                            spring(stiffness = Spring.StiffnessMedium),
                            label = "tabScale"
                        )
                        Box(
                            modifier = Modifier
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) Accent.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { onSelect(index) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.label.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Accent else TextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            val isStore = tabs[selectedIdx].label.contains("Store", ignoreCase = true)

            // ── Right: Action button with circle shadow ──
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(6.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(CircleShape)
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                if (isStore) {
                    IconButton(onClick = { SteamService.requestSync() }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Store", tint = TextPrimary, modifier = Modifier.size(24.dp))
                    }
                } else {
                    IconButton(onClick = {
                        // Open per-game settings (container/shortcut config) for selected game
                        val intent = Intent(context, MainActivity::class.java)
                        intent.putExtra("return_to_unified", true)
                        // Pass the currently selected game's app ID if available
                        val appId = selectedSteamAppId
                        if (appId > 0) {
                            intent.putExtra("create_shortcut_for_app_id", appId)
                            intent.putExtra("create_shortcut_for_app_name", selectedSteamAppName)
                        }
                        context.startActivity(intent)
                    }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = "Game Settings", tint = TextPrimary, modifier = Modifier.size(24.dp))
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            // ── Right: Steam profile avatar with status picker ──
            Box {
                val avatarUrl = persona?.avatarHash?.getAvatarURL()
                    ?: "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/avatars/fe/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb_full.jpg"

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(6.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.5f))
                        .clip(CircleShape)
                        .clickable { showStatusMenu = true }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build(),
                        contentDescription = "Profile",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Status indicator dot
                    val statusColor = when (currentState) {
                        EPersonaState.Online -> StatusOnline
                        EPersonaState.Away -> StatusAway
                        else -> StatusOffline
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                            .offset((-1).dp, (-1).dp)
                            .background(BgDark, CircleShape)
                            .padding(2.dp)
                            .background(statusColor, CircleShape)
                    )
                }

                // ── Status dropdown ──
                DropdownMenu(
                    expanded = showStatusMenu,
                    onDismissRequest = { showStatusMenu = false },
                    modifier = Modifier
                        .width(200.dp)
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                ) {
                    Text(
                        "STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    listOf(
                        Triple(EPersonaState.Online, "Online", StatusOnline),
                        Triple(EPersonaState.Away, "Away", StatusAway),
                        Triple(EPersonaState.Invisible, "Invisible", StatusOffline)
                    ).forEach { (state, label, color) ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(10.dp).background(color, CircleShape))
                                    Text(label, color = TextPrimary)
                                    Spacer(Modifier.weight(1f))
                                    if (currentState == state) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            onClick = {
                                showStatusMenu = false
                                scope.launch { SteamService.setPersonaState(state) }
                            }
                        )
                    }
                }
            }
        }
    }

    // ─── PS5-style Library Carousel ───────────────────────────────────
    @Composable
    fun LibraryCarousel(isLoggedIn: Boolean, steamApps: List<SteamApp>) {
        if (!isLoggedIn) {
            LoginRequiredScreen()
            return
        }

        val installedApps = remember(steamApps) {
            steamApps.filter { SteamService.isAppInstalled(it.id) }
        }

        if (installedApps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyStateMessage("No games installed. Use a Store tab to find and install games.")
            }
            return
        }

        val midIndex = remember(installedApps.size) { installedApps.size / 2 }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = midIndex)

        val centerIdx by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.width / 2
                layoutInfo.visibleItemsInfo.minByOrNull {
                    abs((it.offset + it.size / 2) - viewportCenter)
                }?.index ?: midIndex
            }
        }

        // Track which game is selected for the top-right "Game Settings" button
        val selectedApp = installedApps.getOrNull(centerIdx)
        LaunchedEffect(selectedApp) {
            selectedSteamAppId = selectedApp?.id ?: 0
            selectedSteamAppName = selectedApp?.name ?: ""
        }

        // Use half screen width for content padding so first/last item can center
        val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
        val itemWidth = 140.dp
        val centerPadding = (screenWidthDp - itemWidth) / 2

        Column(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {

            // ── Horizontal carousel ──
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(260.dp),
                contentPadding = PaddingValues(horizontal = centerPadding),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(installedApps) { index, app ->
                    val isCentered = index == centerIdx
                    val targetScale by animateFloatAsState(
                        targetValue = if (isCentered) 1.15f else 0.85f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "capsuleScale"
                    )
                    val shadowElevation by animateFloatAsState(
                        targetValue = if (isCentered) 24f else 2f,
                        spring(stiffness = Spring.StiffnessMedium),
                        label = "shadowElev"
                    )
                    val titleAlpha by animateFloatAsState(
                        targetValue = if (isCentered) 1f else 0.5f,
                        spring(stiffness = Spring.StiffnessMedium),
                        label = "titleAlpha"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(itemWidth)
                            .graphicsLayer {
                                scaleX = targetScale
                                scaleY = targetScale
                            }
                    ) {
                        GameCapsule(
                            app = app,
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(
                                    shadowElevation.dp,
                                    RoundedCornerShape(12.dp),
                                    spotColor = if (isCentered) Color.Black.copy(alpha = 0.6f) else Color.Transparent
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Selected game details ──
            if (selectedApp != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .padding(20.dp)
                ) {
                    Text(
                        selectedApp.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (selectedApp.developer.isNotEmpty()) {
                            Text(selectedApp.developer, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        val installed = SteamService.isAppInstalled(selectedApp.id)
                        Text(
                            if (installed) "● Installed" else "○ Not Installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (installed) StatusOnline else TextSecondary
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val context = LocalContext.current
                        val containerManager = remember { ContainerManager(context) }

                        if (SteamService.isAppInstalled(selectedApp.id)) {
                            Button(
                                onClick = { launchSteamGame(context, containerManager, selectedApp) },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("PLAY", fontWeight = FontWeight.Bold) }
                        }

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, MainActivity::class.java)
                                intent.putExtra("create_shortcut_for_app_id", selectedApp.id)
                                intent.putExtra("create_shortcut_for_app_name", selectedApp.name)
                                intent.putExtra("return_to_unified", true)
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Game Settings", color = TextSecondary) }
                    }
                }
            }
        }
    }

    // ─── Single game capsule for carousel ─────────────────────────────
    @Composable
    private fun GameCapsule(app: SteamApp, modifier: Modifier = Modifier) {
        val context = LocalContext.current

        Column(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .pointerInput(app.id) {
                    detectTapGestures {
                        if (SteamService.isAppInstalled(app.id)) {
                            val containerManager = ContainerManager(context)
                            launchSteamGame(context, containerManager, app)
                        }
                    }
                }
        ) {
            // Artwork — robust CDN fallback chain (most reliable URLs first)
            val imageUrls = listOf(
                app.getCapsuleUrl(),
                app.getCapsuleUrl(large = true),
                "https://cdn.cloudflare.steamstatic.com/steam/apps/${app.id}/library_600x900.jpg",
                app.getHeroUrl(),
                app.getHeaderImageUrl(),
                "https://cdn.cloudflare.steamstatic.com/steam/apps/${app.id}/header.jpg",
                "https://cdn.cloudflare.steamstatic.com/steam/apps/${app.id}/capsule_616x353.jpg",
                "https://cdn.cloudflare.steamstatic.com/steam/apps/${app.id}/library_hero.jpg"
            )
            val imageUrl = imageUrls.firstOrNull { it != null } ?: imageUrls[2]!!

            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(300)
                    .build(),
                contentDescription = app.name,
                modifier = Modifier.fillMaxWidth().height(175.dp),
                contentScale = ContentScale.Crop
            )

            Text(
                text = app.name,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }

    // ─── Steam Store Tab ──────────────────────────────────────────────
    @Composable
    fun SteamStoreTab(isLoggedIn: Boolean, steamApps: List<SteamApp>) {
        if (!isLoggedIn) {
            LoginRequiredScreen()
            return
        }

        var selectedAppForDialog by remember { mutableStateOf<SteamApp?>(null) }
        val context = LocalContext.current

        Column(Modifier.fillMaxSize().padding(16.dp)) {

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(steamApps) { app ->
                    SteamStoreCapsule(app, onClick = { selectedAppForDialog = app })
                }
            }
        }

        if (selectedAppForDialog != null) {
            GameManagerDialog(
                app = selectedAppForDialog!!,
                onDismissRequest = { selectedAppForDialog = null }
            )
        }
    }

    @Composable
    fun SteamStoreCapsule(app: SteamApp, onClick: () -> Unit) {
        val isInstalled = SteamService.isAppInstalled(app.id)
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .width(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardDark)
                .clickable(onClick = onClick)
        ) {
            Box {
                val imageUrl = app.getHeaderImageUrl()
                    ?: app.getCapsuleUrl()
                    ?: "https://cdn.akamai.steamstatic.com/steam/apps/${app.id}/header.jpg"

                AsyncImage(
                    model = ImageRequest.Builder(context).data(imageUrl).crossfade(300).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(165.dp),
                    contentScale = ContentScale.Crop
                )

                if (isInstalled) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Installed",
                        tint = StatusOnline,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp)
                    )
                }
            }

            Text(
                text = app.name,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    // ─── Downloads Tab ────────────────────────────────────────────────
    @Composable
    fun DownloadsTab() {
        val downloads = remember { mutableStateListOf<Pair<String, DownloadInfo>>() }

        LaunchedEffect(Unit) {
            while (true) {
                val currentDownloads = DownloadService.getAllDownloads()
                downloads.clear()
                downloads.addAll(currentDownloads)
                kotlinx.coroutines.delay(1000)
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("ACTIVE DOWNLOADS", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Spacer(Modifier.height(16.dp))

            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(downloads) { (id, info) ->
                    DownloadItemDeck(id, info)
                }
                if (downloads.isEmpty()) {
                    item { EmptyStateMessage("No active downloads.") }
                }
            }
        }
    }

    @Composable
    fun DownloadItemDeck(id: String, info: DownloadInfo) {
        val progress by remember(info) { mutableStateOf(info.getProgress()) }
        val status by info.getStatusFlow().collectAsState()
        val statusMessage by info.getStatusMessageFlow().collectAsState()
        val appId = id.removePrefix("STEAM_").toIntOrNull() ?: 0
        var app by remember(appId) { mutableStateOf<SteamApp?>(null) }
        val context = LocalContext.current

        LaunchedEffect(appId) {
            withContext(Dispatchers.IO) { app = db.steamAppDao().findApp(appId) }
        }

        Surface(
            color = CardDark,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(app?.getHeaderImageUrl() ?: app?.getCapsuleUrl())
                        .crossfade(300).build(),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(app?.name ?: "Unknown Game", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Status: ${status.name}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(CircleShape),
                        color = Accent,
                        trackColor = Color.Black.copy(alpha = 0.3f)
                    )
                }

                IconButton(onClick = { info.cancel() }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFFFF6B6B))
                }
            }
        }
    }

    // ─── Store placeholder tabs ───────────────────────────────────────
    @Composable
    fun StorePlaceholderTab(storeName: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Store,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("$storeName", style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Coming soon", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { /* TODO: Wire sign-in flow */ },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Sign In to $storeName")
                }
            }
        }
    }

    // ─── Game Manager Dialog ──────────────────────────────────────────
    @Composable
    fun GameManagerDialog(app: SteamApp, onDismissRequest: () -> Unit) {
        val context = LocalContext.current
        var isLoading by remember { mutableStateOf(true) }
        var depots by remember { mutableStateOf<Map<Int, DepotInfo>>(emptyMap()) }
        var dlcApps by remember { mutableStateOf<List<SteamApp>>(emptyList()) }
        val selectedDlcIds = remember { mutableStateListOf<Int>() }
        var customPath by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        val folderPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri -> uri?.let { customPath = getPathFromTreeUri(it) } }

        LaunchedEffect(app.id) {
            withContext(Dispatchers.IO) {
                depots = SteamService.getDownloadableDepots(app.id)
                dlcApps = db.steamAppDao().findDownloadableDLCApps(app.id) ?: emptyList()
                isLoading = false
            }
        }

        Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f),
                shape = RoundedCornerShape(16.dp),
                color = CardDark
            ) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        Text(app.name, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                        Spacer(Modifier.height(8.dp))

                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            val totalInstallSize = depots.values.sumOf { it.manifests["public"]?.size ?: 0L }
                            val totalDownloadSize = depots.values.sumOf { it.manifests["public"]?.download ?: 0L }

                            val effectivePath = customPath ?: SteamService.defaultAppInstallPath
                            val availableBytes = try { StorageUtils.getAvailableSpace(effectivePath) } catch (e: Exception) { 0L }
                            val hasEnoughSpace = availableBytes >= totalInstallSize

                            Text("Standard Install", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(
                                "Download: ${StorageUtils.formatBinarySize(totalDownloadSize)} • Install: ${StorageUtils.formatBinarySize(totalInstallSize)}",
                                color = if (hasEnoughSpace) TextSecondary else Color(0xFFFF6B6B)
                            )
                            Text("Available: ${StorageUtils.formatBinarySize(availableBytes)}", color = if (hasEnoughSpace) TextSecondary else Color(0xFFFF6B6B))

                            if (dlcApps.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text("DLCs Available", color = TextPrimary, fontWeight = FontWeight.Bold)
                                dlcApps.forEach { dlc ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            if (selectedDlcIds.contains(dlc.id)) selectedDlcIds.remove(dlc.id)
                                            else selectedDlcIds.add(dlc.id)
                                        }
                                    ) {
                                        Checkbox(
                                            checked = selectedDlcIds.contains(dlc.id),
                                            onCheckedChange = { if (it) selectedDlcIds.add(dlc.id) else selectedDlcIds.remove(dlc.id) }
                                        )
                                        Text(dlc.name, color = TextPrimary)
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            Text("Install Location", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { folderPickerLauncher.launch(null) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(if (customPath == null) "Choose Custom Path" else "Path: $customPath", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (customPath != null) {
                                    IconButton(onClick = { customPath = null }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear Custom Path", tint = TextPrimary)
                                    }
                                }
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismissRequest) { Text("Cancel", color = TextSecondary) }
                            Spacer(Modifier.width(8.dp))

                            val totalInstallSize = depots.values.sumOf { it.manifests["public"]?.size ?: 0L }
                            val effectivePath = customPath ?: SteamService.defaultAppInstallPath
                            val availableBytes = try { StorageUtils.getAvailableSpace(effectivePath) } catch (e: Exception) { 0L }
                            val isInstallEnabled = availableBytes >= totalInstallSize

                            Button(
                                enabled = isInstallEnabled,
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        SteamService.downloadApp(app.id, selectedDlcIds.toList(), false, customPath)
                                        withContext(Dispatchers.Main) { onDismissRequest() }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isInstallEnabled) Color(0xFF5c7e10) else Color.Gray),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Install") }
                        }
                    }
                }
            }
        }
    }

    private fun getPathFromTreeUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val path = docId.substringAfter(":")
                val externalStorage = android.os.Environment.getExternalStorageDirectory()
                if (path.isEmpty()) externalStorage.path else "${externalStorage.path}/$path"
            } else if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    val volumeId = parts[0]
                    val path = parts[1]
                    if (path.isEmpty()) "/storage/$volumeId" else "/storage/$volumeId/$path"
                } else null
            } else docId
        } catch (e: Exception) { uri.path }
    }

    // ─── Game launch with A: drive mounting ───────────────────────────
    private fun launchSteamGame(context: android.content.Context, containerManager: ContainerManager, app: SteamApp) {
        val gameInstallPath = SteamService.getAppDirPath(app.id)
        val gameDir = java.io.File(gameInstallPath)
        if (!gameDir.exists()) {
            android.widget.Toast.makeText(context, "Game not installed: ${app.name}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Try to find an existing shortcut first
        val shortcut = containerManager.loadShortcuts().find {
            it.getExtra("app_id") == app.id.toString()
        }

        if (shortcut != null) {
            // Existing shortcut: mount A: drive to game install path on its container
            mountADrive(shortcut.container, gameInstallPath)
            val intent = Intent(context, XServerDisplayActivity::class.java)
            intent.putExtra("container_id", shortcut.container.id)
            intent.putExtra("shortcut_path", shortcut.file.path)
            intent.putExtra("shortcut_name", shortcut.name)
            context.startActivity(intent)
        } else {
            // No shortcut — get or auto-create a container 
            var containers = containerManager.getContainers()
            var container = containers.firstOrNull()
            if (container == null) {
                // Auto-create a default container
                try {
                    val data = org.json.JSONObject()
                    data.put("name", "Default")
                    data.put("wineVersion", "proton-9.0-x86_64")
                    val contentsManager = com.winlator.cmod.contents.ContentsManager(context)
                    contentsManager.syncContents()
                    container = containerManager.createContainer(data, contentsManager)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (container == null) {
                android.widget.Toast.makeText(context, "Failed to create container. Open Game Settings first.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            mountADrive(container, gameInstallPath)

            // Find the first .exe in the game directory
            val exeFile = findGameExe(gameDir)
            if (exeFile != null) {
                container.executablePath = "A:\\${exeFile.relativeTo(gameDir).path.replace('/', '\\')}"
            }
            container.saveData()

            val intent = Intent(context, XServerDisplayActivity::class.java)
            intent.putExtra("container_id", container.id)
            context.startActivity(intent)
        }
    }

    private fun mountADrive(container: com.winlator.cmod.container.Container, gamePath: String) {
        val currentDrives = container.drives ?: com.winlator.cmod.container.Container.DEFAULT_DRIVES
        val sb = StringBuilder()
        for (drive in com.winlator.cmod.container.Container.drivesIterator(currentDrives)) {
            if (drive[0] != "A") {
                sb.append(drive[0]).append(':').append(drive[1])
            }
        }
        sb.append("A:").append(gamePath)
        container.drives = sb.toString()
    }

    private fun findGameExe(dir: java.io.File): java.io.File? {
        // Prefer setup/launcher executables, then any .exe
        val preferred = listOf("launcher.exe", "game.exe", "start.exe")
        dir.walkTopDown().maxDepth(3).forEach { file ->
            if (file.extension.equals("exe", ignoreCase = true) &&
                preferred.any { file.name.equals(it, ignoreCase = true) }) {
                return file
            }
        }
        // Fallback: first .exe found
        return dir.walkTopDown().maxDepth(3).firstOrNull {
            it.extension.equals("exe", ignoreCase = true) &&
            !it.name.contains("unins", ignoreCase = true) &&
            !it.name.contains("redist", ignoreCase = true) &&
            !it.name.contains("setup", ignoreCase = true)
        }
    }


    @Composable
    fun EmptyStateMessage(message: String) {
        Text(message, color = TextSecondary, modifier = Modifier.padding(16.dp))
    }

    @Composable
    fun LoginRequiredScreen() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Person, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Please sign in to see your Steam Library", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { startActivity(Intent(this@UnifiedActivity, SteamLoginActivity::class.java)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Sign in to Steam", fontWeight = FontWeight.Bold) }
            }
        }
    }

    // ─── Filter panel ─────────────────────────────────────────────────
    @Composable
    private fun FilterPanel(
        visible: Boolean,
        onDismiss: () -> Unit,
        aioMode: Boolean,
        onAioToggle: (Boolean) -> Unit,
        storeVisible: SnapshotStateMap<String, Boolean>,
        contentFilters: SnapshotStateMap<String, Boolean>
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .padding(start = 16.dp, bottom = 72.dp)
                    .width(280.dp),
                shape = RoundedCornerShape(16.dp),
                color = SurfaceDark,
                shadowElevation = 16.dp,
                tonalElevation = 4.dp
            ) {
                Column(Modifier.padding(20.dp)) {
                    // Header
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("FILTERS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))

                    // AIO Mode toggle
                    FilterToggleRow("AIO Store Mode", aioMode) { onAioToggle(it) }

                    Spacer(Modifier.height(16.dp))
                    Text("STORES", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))

                    listOf("steam" to "Steam", "epic" to "Epic", "gog" to "GOG", "amazon" to "Amazon").forEach { (key, label) ->
                        FilterToggleRow(label, storeVisible[key] ?: true) { storeVisible[key] = it }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("CONTENT TYPES", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))

                    listOf("games" to "Games", "dlc" to "DLC", "applications" to "Applications", "tools" to "Tools").forEach { (key, label) ->
                        FilterToggleRow(label, contentFilters[key] ?: false) { contentFilters[key] = it }
                    }
                }
            }
        }
    }

    @Composable
    private fun FilterToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggle(!checked) }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Accent,
                    checkedTrackColor = Accent.copy(alpha = 0.3f),
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = TextSecondary.copy(alpha = 0.15f)
                ),
                modifier = Modifier.height(24.dp)
            )
        }
    }
}

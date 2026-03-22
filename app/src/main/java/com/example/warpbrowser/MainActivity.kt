package com.example.warpbrowser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.Image
import com.example.warpbrowser.network.GitHubFetcher
import com.example.warpbrowser.ui.theme.WarpBrowserTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// Tab data class
data class BrowserTab(
    val id: Int,
    val url: String = "",
    val warpCode: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val title: String = "New Tab",
    val thumbnail: android.graphics.Bitmap? = null
)

// URL conversion utilities
fun expandShorthandUrl(url: String): String {
    // Handle @user/repo/file.warp format or @user only
    if (url.startsWith("@")) {
        val parts = url.substring(1).split("/")
        if (parts.size == 1) {
            // @user only -> @user/index-warp/index.warp
            val user = parts[0]
            return "https://raw.githubusercontent.com/$user/index-warp/main/index.warp"
        } else if (parts.size >= 2) {
            val owner = parts[0]
            val repo = parts[1]
            val file = if (parts.size >= 3) {
                parts.drop(2).joinToString("/")
            } else {
                "index.warp"
            }
            // Add .warp extension if missing
            val fileWithExtension = if (file.endsWith(".warp")) file else "$file.warp"
            return "https://raw.githubusercontent.com/$owner/$repo/main/$fileWithExtension"
        }
    }
    // Handle GitHub blob URLs
    val blobPattern = Regex("""https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)""")
    val match = blobPattern.find(url)
    return if (match != null) {
        val (owner, repo, ref, path) = match.destructured
        // Add .warp extension if missing
        val pathWithExtension = if (path.endsWith(".warp")) path else "$path.warp"
        "https://raw.githubusercontent.com/$owner/$repo/$ref/$pathWithExtension"
    } else {
        // Add .warp extension if missing for raw URLs
        if (url.startsWith("https://raw.githubusercontent.com") && !url.endsWith(".warp")) {
            "$url.warp"
        } else {
            url
        }
    }
}

fun toShorthandUrl(url: String): String {
    // Handle GitHub raw URLs
    val rawPattern = Regex("""https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)""")
    val rawMatch = rawPattern.find(url)
    if (rawMatch != null) {
        val (owner, repo, ref, path) = rawMatch.destructured
        // Remove .warp extension for display
        val displayPath = if (path.endsWith(".warp")) path.dropLast(5) else path
        if (ref == "main") {
            return "@$owner/$repo/$displayPath"
        }
        return "@$owner/$repo/$ref/$displayPath"
    }
    // Handle GitHub blob URLs
    val blobPattern = Regex("""https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)""")
    val match = blobPattern.find(url)
    return if (match != null) {
        val (owner, repo, ref, path) = match.destructured
        // Remove .warp extension for display
        val displayPath = if (path.endsWith(".warp")) path.dropLast(5) else path
        if (ref == "main") {
            "@$owner/$repo/$displayPath"
        } else {
            "@$owner/$repo/$ref/$displayPath"
        }
    } else {
        url
    }
}

class MainActivity : ComponentActivity() {
    private val defaultUrl = "https://github.com/search3958/Warp/blob/main/demo.warp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WarpBrowserTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WarpBrowserApp()
                }
            }
        }
    }
}

@Composable
fun WarpBrowserApp() {
    val context = LocalContext.current
    val fetcher = remember { GitHubFetcher() }
    
    // Tab management - use mutableStateListOf for proper state management
    val tabs = remember { mutableStateListOf<BrowserTab>() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    
    var nextTabId by remember { mutableStateOf(1) }
    val defaultUrl = "https://github.com/search3958/Warp/blob/main/demo.warp"
    
    // Helper function to rebuild tabs list
    fun rebuildTabs(newTabs: List<BrowserTab>) {
        val currentSize = tabs.size
        for (i in 0 until newTabs.size) {
            if (i < currentSize) {
                tabs[i] = newTabs[i]
            } else {
                tabs.add(newTabs[i])
            }
        }
        // Remove extra tabs if new list is smaller
        while (tabs.size > newTabs.size) {
            tabs.removeAt(tabs.size - 1)
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(it)?.use { inputStream ->
                        inputStream.bufferedReader().readText()
                    }
                    if (content != null && pagerState.currentPage < tabs.size) {
                        val currentTab = tabs[pagerState.currentPage]
                        val updatedList = ArrayList(tabs)
                        updatedList[pagerState.currentPage] = currentTab.copy(
                            warpCode = content,
                            errorMessage = null,
                            title = "Local File"
                        )
                        rebuildTabs(updatedList)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun loadUrlInTab(tabIndex: Int, url: String) {
        if (tabIndex >= tabs.size) return

        val currentTab = tabs[tabIndex]
        // Update tab to loading state
        val newList = ArrayList(tabs)
        newList[tabIndex] = currentTab.copy(
            isLoading = true,
            errorMessage = null,
            url = url,
            title = url.split("/").lastOrNull() ?: "New Tab"
        )
        rebuildTabs(newList)

        val rawUrl = expandShorthandUrl(url)
        
        // Capture tabIndex in a local variable to avoid issues if tabs change
        val capturedTabIndex = tabIndex

        scope.launch {
            fetcher.fetchRawContent(rawUrl).onSuccess { content ->
                val updatedList = ArrayList(tabs)
                if (capturedTabIndex < updatedList.size) {
                    updatedList[capturedTabIndex] = updatedList[capturedTabIndex].copy(
                        warpCode = content,
                        isLoading = false
                    )
                    rebuildTabs(updatedList)
                }
            }.onFailure { error ->
                val updatedList = ArrayList(tabs)
                if (capturedTabIndex < updatedList.size) {
                    updatedList[capturedTabIndex] = updatedList[capturedTabIndex].copy(
                        errorMessage = error.message,
                        isLoading = false
                    )
                    rebuildTabs(updatedList)
                }
                Toast.makeText(context, "Network error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun addNewTab() {
        val newTab = BrowserTab(id = nextTabId++, url = "", title = "New Tab")
        tabs.add(newTab)
        scope.launch {
            pagerState.animateScrollToPage(tabs.size - 1)
        }
    }

    fun closeTab(tabId: Int) {
        if (tabs.size == 1) {
            // Don't close the last tab, just clear it
            val updatedList = ArrayList(tabs)
            updatedList[0] = updatedList[0].copy(
                url = "",
                warpCode = null,
                errorMessage = null,
                title = "New Tab"
            )
            rebuildTabs(updatedList)
            return
        }
        
        val tabIndex = tabs.indexOfFirst { it.id == tabId }
        if (tabIndex == -1) return
        
        val wasCurrent = tabIndex == pagerState.currentPage
        tabs.removeAt(tabIndex)
        
        if (wasCurrent && tabIndex >= tabs.size) {
            scope.launch {
                pagerState.animateScrollToPage(tabs.size - 1)
            }
        }
    }

    fun switchToTab(index: Int) {
        scope.launch {
            pagerState.animateScrollToPage(index)
        }
    }

    // Load initial URL after all functions are defined
    LaunchedEffect(Unit) {
        tabs.add(BrowserTab(id = 0, url = defaultUrl, title = "demo.warp"))
        loadUrlInTab(0, defaultUrl)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Content area - with padding for bottom toolbar
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp)
        ) { page ->
            val tab = tabs.getOrNull(page)
            if (tab != null) {
                WarpContentView(
                    tab = tab,
                    onLoadUrl = { url -> loadUrlInTab(page, url) }
                )
            }
        }

        // Bottom Chrome-like toolbar - always on top at bottom
        ChromeToolbar(
            currentTabIndex = pagerState.currentPage,
            tabs = tabs,
            currentPage = pagerState.currentPage,
            onLoadUrl = { url ->
                loadUrlInTab(pagerState.currentPage, url)
            },
            onTabSelected = { index ->
                // Force tab switch
                scope.launch {
                    pagerState.animateScrollToPage(index)
                }
            },
            onTabClosed = { closeTab(it) },
            onNewTab = { addNewTab() },
            onTabUpdated = { index, updatedTab ->
                val newList = ArrayList(tabs)
                newList[index] = updatedTab
                rebuildTabs(newList)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

@Composable
fun ChromeToolbar(
    currentTabIndex: Int,
    tabs: List<BrowserTab>,
    currentPage: Int,
    onLoadUrl: (String) -> Unit,
    onTabSelected: (Int) -> Unit,
    onTabClosed: (Int) -> Unit,
    onNewTab: () -> Unit,
    onTabUpdated: (Int, BrowserTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTab = tabs.getOrNull(currentTabIndex)
    var urlInput by remember { mutableStateOf(currentTab?.url ?: "") }
    var showMenu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }

    // Track loading state per tab, not globally
    val isReloading = currentTab?.isLoading == true

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(it)?.use { inputStream ->
                        inputStream.bufferedReader().readText()
                    }
                    if (content != null && currentPage < tabs.size) {
                        val currentTab = tabs[currentPage]
                        val updatedTab = currentTab.copy(
                            warpCode = content,
                            errorMessage = null,
                            title = "Local File"
                        )
                        onTabUpdated(currentPage, updatedTab)
                    }
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    }

    LaunchedEffect(currentTab?.url) {
        urlInput = if (currentTab?.url != null) toShorthandUrl(currentTab.url) else ""
    }

    // Toolbar surface at bottom
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tabs button (square with number) - placed on right
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { showTabs = true },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text(
                        text = "${tabs.size}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Menu button (three dots) - placed on right, before tabs
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Dropdown menu
                if (showMenu) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open .warp file") },
                            onClick = {
                                filePickerLauncher.launch("*/*")
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }

            // Address bar (centered, takes remaining space)
            Surface(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(24.dp)
            ) {
                TextField(
                    value = urlInput,
                    onValueChange = { newUrl -> urlInput = newUrl },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = {
                        Text(
                            text = "Search or type URL",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Reload button
                            IconButton(
                                onClick = {
                                    val currentUrl = urlInput
                                    if (currentUrl.isNotBlank()) {
                                        onLoadUrl(currentUrl)
                                    }
                                },
                                modifier = Modifier.size(28.dp),
                                enabled = !isReloading && urlInput.isNotBlank()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isReloading)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                                )
                            }

                            // Go button
                            if (urlInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        val currentUrl = urlInput
                                        if (currentUrl.isNotBlank()) {
                                            onLoadUrl(currentUrl)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                    enabled = !isReloading
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Go",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    // Tab switcher overlay
    if (showTabs) {
        TabSwitcherOverlay(
            tabs = tabs,
            currentPage = currentPage,
            onTabSelected = {
                onTabSelected(it)
                showTabs = false
            },
            onTabClosed = { onTabClosed(it) },
            onNewTab = {
                onNewTab()
                showTabs = false
            },
            onDismiss = { showTabs = false }
        )
    }
}

@Composable
fun TabSwitcherOverlay(
    tabs: List<BrowserTab>,
    currentPage: Int,
    onTabSelected: (Int) -> Unit,
    onTabClosed: (Int) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .systemBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tabs (${tabs.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close"
                )
            }
        }

        // Tab grid - 2 columns with scroll
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // New tab card
            item {
                androidx.compose.material3.Card(
                    onClick = onNewTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f),
                    shape = RoundedCornerShape(16.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New tab",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "New tab",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Existing tabs
            items(tabs.size) { index ->
                val tab = tabs[index]
                val isSelected = index == currentPage
                androidx.compose.material3.Card(
                    onClick = { onTabSelected(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f),
                    shape = RoundedCornerShape(16.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (isSelected)
                        androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    else null
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            // Thumbnail preview
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (tab.thumbnail != null) {
                                    Image(
                                        bitmap = tab.thumbnail.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else if (tab.errorMessage != null) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                } else if (tab.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }

                            // Tab title
                            Text(
                                text = tab.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            // Close button
                            IconButton(
                                onClick = { onTabClosed(tab.id) },
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.End)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WarpContentView(
    tab: BrowserTab,
    onLoadUrl: (String) -> Unit
) {
    when {
        tab.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        tab.errorMessage != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Error: ${tab.errorMessage}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        tab.warpCode != null -> {
            WarpApp(code = tab.warpCode)
        }
        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Enter a URL to load a .warp file",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Try: @search3958/Warp/demo.warp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ==========================================
// Warp State
// ==========================================
class WarpState(context: Context) {
    var currentScreen by mutableStateOf("main")
    val vars = mutableStateMapOf<String, String>()
    val visibility = mutableStateMapOf<String, Boolean>()
    val status = mutableStateMapOf<String, String>()
    val dynamicNodes = mutableStateMapOf<String, List<WarpNode.Component>>()

    private val prefs = context.getSharedPreferences("warp_prefs", Context.MODE_PRIVATE)

    fun getVar(name: String): String {
        val cached = vars[name]
        if (cached != null) return cached
        return if (name.startsWith("~~")) {
            val saved = prefs.getString(name, "") ?: ""
            vars[name] = saved
            saved
        } else {
            ""
        }
    }

    fun setVar(name: String, value: String) {
        vars[name] = value
        if (name.startsWith("~~")) {
            prefs.edit().putString(name, value).apply()
        }
    }
}

// ==========================================
// Warp AST Nodes
// ==========================================
sealed class WarpNode {
    data class Component(
        val name: String,
        val props: Map<String, List<String>>,
        val events: Map<String, String>,
        val children: List<WarpNode>
    ) : WarpNode()

    data class Script(
        val name: String,
        val blocks: List<ScriptBlock>
    ) : WarpNode()
}

data class ScriptBlock(
    val type: String,
    val condition: String,
    val actions: String
)

// ==========================================
// Warp Engine
// ==========================================
class WarpEngine(val code: String, val state: WarpState) {
    val ast: List<WarpNode>

    init {
        ast = parseCode(code)
        initState(ast)
    }

    private fun initState(nodes: List<WarpNode>) {
        fun walk(node: WarpNode) {
            if (node is WarpNode.Component) {
                node.props.forEach { (k, v) ->
                    if (k.startsWith("--") || k.startsWith("~~")) {
                        if (state.getVar(k).isEmpty()) {
                            state.setVar(k, evalExpr(v))
                        }
                    }
                }
                node.children.forEach { walk(it) }
            }
        }
        nodes.forEach { walk(it) }
    }

    private fun tokenize(code: String): List<String> {
        val tokens = mutableListOf<String>()
        var pos = 0
        while (pos < code.length) {
            val c = code[pos]
            if (c.isWhitespace()) { pos++; continue }
            if (c == '/' && pos + 1 < code.length && code[pos + 1] == '/') {
                while (pos < code.length && code[pos] != '\n') pos++
                continue
            }
            if (c == '"' || c == '\'') {
                val quote = c
                var str = quote.toString()
                pos++
                while (pos < code.length) {
                    if (code[pos] == '\\') {
                        str += code[pos]; pos++
                        if (pos < code.length) { str += code[pos]; pos++ }
                        continue
                    }
                    if (code[pos] == quote) { str += code[pos]; pos++; break }
                    str += code[pos]; pos++
                }
                tokens.add(str)
                continue
            }
            if ((c == '-' && pos + 1 < code.length && code[pos + 1] == '-') ||
                (c == '~' && pos + 1 < code.length && code[pos + 1] == '~')) {
                var word = c.toString() + code[pos+1].toString()
                pos += 2
                while (pos < code.length && !code[pos].isWhitespace() && 
                    !listOf('(', ')', '{', '}', ',', ':', '=', '+', '-', '*', '/', '"', '\'', ';').contains(code[pos])) {
                    word += code[pos]; pos++
                }
                tokens.add(word)
                continue
            }
            if (listOf('(', ')', '{', '}', ',', ':', '=', '+', '-', '*', '/', ';').contains(c)) {
                tokens.add(c.toString()); pos++; continue
            }
            var word = ""
            while (pos < code.length && !code[pos].isWhitespace() && 
                !listOf('(', ')', '{', '}', ',', ':', '=', '+', '-', '*', '/', '"', '\'', ';').contains(code[pos])) {
                word += code[pos]; pos++
            }
            if (word.isNotEmpty()) tokens.add(word)
        }
        return tokens
    }

    private fun parseCode(code: String): List<WarpNode> {
        val tokens = tokenize(code)
        var pos = 0

        fun isReservedFunc(name: String): Boolean {
            return listOf("reset", "calc", "script", "add", "del", "clr", "show", "hide", "random", "wait").contains(name) || 
                name.startsWith("setScreen")
        }

        fun parseNode(): WarpNode? {
            if (pos >= tokens.size) return null
            val token = tokens[pos]

            if (token.startsWith("@")) {
                val name = token.substring(1)
                val blocks = mutableListOf<ScriptBlock>()
                pos++
                val endToken = if (pos < tokens.size && tokens[pos] == "{") "}" else ")"
                pos++
                while (pos < tokens.size && tokens[pos] != endToken) {
                    val blockTypeToken = tokens[pos]
                    if (blockTypeToken == "if" || blockTypeToken == "elseIf") {
                        val blockType = blockTypeToken
                        pos++
                        if (pos < tokens.size && tokens[pos] == ":") pos++
                        val condTokens = mutableListOf<String>()
                        val condOpen = if (pos < tokens.size && tokens[pos] == "(") "(" else ""
                        if (condOpen.isNotEmpty()) pos++
                        var condParenCount = if (condOpen.isNotEmpty()) 1 else 0
                        while (pos < tokens.size) {
                            val ct = tokens[pos]
                            if (condOpen.isNotEmpty()) {
                                if (ct == "(") condParenCount++
                                if (ct == ")") condParenCount--
                                if (condParenCount == 0) { pos++; break }
                            } else {
                                if (ct == "{" || ct == "(" || ct == endToken) break
                            }
                            condTokens.add(ct); pos++
                        }
                        val condition = condTokens.joinToString("")
                        val blockEndToken = if (pos < tokens.size && tokens[pos] == "{") "}" else ")"
                        pos++
                        val actionTokens = mutableListOf<String>()
                        var parenCount = 1
                        while (pos < tokens.size) {
                            if (tokens[pos] == "{" || tokens[pos] == "(") parenCount++
                            if (tokens[pos] == "}" || tokens[pos] == ")") parenCount--
                            if (parenCount == 0) break
                            actionTokens.add(tokens[pos]); pos++
                        }
                        blocks.add(ScriptBlock(blockType, condition, actionTokens.joinToString("")))
                        if (pos < tokens.size) pos++
                    } else { pos++ }
                    if (pos < tokens.size && tokens[pos] == ",") pos++
                }
                if (pos < tokens.size) pos++
                return WarpNode.Script(name, blocks)
            }

            if (token == "if" && pos + 1 < tokens.size && tokens[pos + 1] == ":") {
                val name = "if"
                pos += 2
                val condTokens = mutableListOf<String>()
                while (pos < tokens.size && tokens[pos] != "(" && tokens[pos] != "{") {
                    condTokens.add(tokens[pos]); pos++
                }
                val props = mutableMapOf<String, List<String>>()
                props["condition"] = condTokens
                val children = mutableListOf<WarpNode>()
                val endToken = if (pos < tokens.size && tokens[pos] == "{") "}" else ")"
                pos++
                while (pos < tokens.size && tokens[pos] != endToken) {
                    val child = parseNode()
                    if (child != null) children.add(child)
                    else if (tokens[pos] != endToken && tokens[pos] != ",") pos++
                    if (pos < tokens.size && tokens[pos] == ",") pos++
                }
                if (pos < tokens.size) pos++
                return WarpNode.Component(name, props, emptyMap(), children)
            }

            if (pos + 1 < tokens.size && (tokens[pos + 1] == "(" || tokens[pos + 1] == "{")) {
                val name = token
                val openToken = tokens[pos + 1]
                val closeToken = if (openToken == "(") ")" else "}"
                val props = mutableMapOf<String, List<String>>()
                val events = mutableMapOf<String, String>()
                val children = mutableListOf<WarpNode>()
                pos += 2
                while (pos < tokens.size && tokens[pos] != closeToken) {
                    val t = tokens[pos]
                    if (pos + 1 < tokens.size && (tokens[pos + 1] == "(" || tokens[pos + 1] == "{") && !isReservedFunc(t)) {
                        val child = parseNode()
                        if (child != null) children.add(child)
                        if (pos < tokens.size && tokens[pos] == ",") pos++
                        continue
                    } else if (pos + 1 < tokens.size && tokens[pos + 1] == ":") {
                        val key = t
                        pos += 2
                        val expr = mutableListOf<String>()
                        val valOpen = if (pos < tokens.size && (tokens[pos] == "(" || tokens[pos] == "{")) tokens[pos] else ""
                        val valClose = if (valOpen == "(") ")" else if (valOpen == "{") "}" else ""
                        if (valOpen.isNotEmpty()) pos++
                        var parenCount = if (valOpen.isNotEmpty()) 1 else 0
                        while (pos < tokens.size) {
                            val currentToken = tokens[pos]
                            if (valOpen.isNotEmpty()) {
                                if (currentToken == valOpen) parenCount++
                                else if (currentToken == valClose) parenCount--
                                if (parenCount == 0) { pos++; break }
                            } else {
                                if (currentToken == closeToken || currentToken == ",") break
                                if (pos + 1 < tokens.size && (tokens[pos + 1] == "(" || tokens[pos + 1] == "{") && !isReservedFunc(currentToken)) break
                                if (pos + 1 < tokens.size && tokens[pos + 1] == ":") break
                            }
                            expr.add(currentToken); pos++
                        }
                        if (key == "oneClick" || key == "longPress" || key == "onValueChange" || key == "output") {
                            events[key] = expr.joinToString("")
                        } else {
                            props[key] = expr
                        }
                        if (pos < tokens.size && tokens[pos] == ",") pos++
                        continue
                    }
                    pos++
                }
                if (pos < tokens.size) pos++
                return WarpNode.Component(name, props, events, children)
            }
            pos++
            return null
        }

        val parsedAst = mutableListOf<WarpNode>()
        while (pos < tokens.size) {
            val node = parseNode()
            if (node != null) parsedAst.add(node)
        }
        return parsedAst
    }

    fun evalExpr(exprArr: List<String>?): String {
        if (exprArr.isNullOrEmpty()) return ""
        var result = ""
        for (t in exprArr) {
            val trimmed = t.trim()
            if (trimmed == "+" || trimmed.isEmpty()) continue
            if (trimmed.startsWith("--") || trimmed.startsWith("~~")) {
                result += state.getVar(trimmed)
            } else if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || 
                (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                var inner = trimmed.substring(1, trimmed.length - 1)
                inner = inner.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
                    .replace("\\(", "(").replace("\\)", ")").replace("\\:", ":").replace("\\ ", " ")
                result += inner
            } else if (trimmed == "null") {
                // skip
            } else {
                if (!listOf("(", ")", "{", "}", ",", ":", "=", "+", "-", "*", "/", ";").contains(trimmed)) {
                    result += trimmed
                }
            }
        }
        return result
    }

    fun evaluateRHS(exprStr: String): String {
        var currentExpr = exprStr.trim()
        if (currentExpr.startsWith("(") && currentExpr.endsWith(")")) 
            currentExpr = currentExpr.substring(1, currentExpr.length - 1).trim()
        if (currentExpr == "null" || currentExpr.isEmpty()) return ""
        if (currentExpr == "true") return "true"
        if (currentExpr == "false") return "false"

        if (currentExpr.startsWith("calc{") || currentExpr.startsWith("calc(")) {
            val open = if (currentExpr.startsWith("calc{")) '{' else '('
            val close = if (open == '{') '}' else ')'
            var inner = currentExpr.substring(5, currentExpr.lastIndexOf(close))
            val regex = Regex("(--|~~)[a-zA-Z0-9_-]+")
            inner = regex.replace(inner) { matchResult ->
                state.getVar(matchResult.value).ifEmpty { "0" }
            }
            return try { evalBasicMath(inner).toString().replace(".0", "") } catch (e: Exception) { "0" }
        }

        if (currentExpr.contains(".replace{") || currentExpr.contains(".replace(")) {
            val idx = if (currentExpr.contains(".replace{")) currentExpr.indexOf(".replace{") 
                else currentExpr.indexOf(".replace(")
            val open = currentExpr[idx + 8]
            val close = if (open == '{') '}' else ')'
            val base = currentExpr.substring(0, idx).trim()
            val argsStr = currentExpr.substring(idx + 9, currentExpr.lastIndexOf(close))
            val args = splitByComma(argsStr)
            if (args.size >= 2) {
                val old = evaluateRHS(args[0])
                val new = evaluateRHS(args[1])
                return evaluateRHS(base).replace(old, new)
            }
        }

        if (currentExpr.startsWith("random{") || currentExpr.startsWith("random(")) {
            val open = if (currentExpr.startsWith("random{")) '{' else '('
            val close = if (open == '{') '}' else ')'
            val args = splitByComma(currentExpr.substring(7, currentExpr.lastIndexOf(close)))
            if (args.size == 2) {
                val min = evaluateRHS(args[0]).toIntOrNull() ?: 0
                val max = evaluateRHS(args[1]).toIntOrNull() ?: 100
                return Random.nextInt(min, max + 1).toString()
            }
        }

        val parts = mutableListOf<String>()
        var current = ""
        var inQuote = false
        var pLevel = 0
        for (c in currentExpr) {
            if (c == '"' || c == '\'') inQuote = !inQuote
            if (!inQuote) {
                if (c == '(' || c == '{') pLevel++
                if (c == ')' || c == '}') pLevel--
            }
            if (c == '+' && !inQuote && pLevel == 0) {
                parts.add(current.trim())
                current = ""
            } else { current += c }
        }
        if (current.isNotEmpty()) parts.add(current.trim())

        var result = ""
        for (p in parts) {
            if ((p.startsWith("\"") && p.endsWith("\"")) || (p.startsWith("'") && p.endsWith("'"))) {
                var inner = p.substring(1, p.length - 1)
                inner = inner.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
                result += inner
            } else if (p.startsWith("--") || p.startsWith("~~")) {
                result += state.getVar(p)
            } else { result += p }
        }
        return result
    }

    private fun splitByComma(s: String): List<String> {
        val res = mutableListOf<String>()
        var curr = ""
        var inQuote = false
        var pLevel = 0
        for (c in s) {
            if (c == '"' || c == '\'') inQuote = !inQuote
            if (!inQuote) {
                if (c == '(' || c == '{') pLevel++
                if (c == ')' || c == '}') pLevel--
            }
            if (c == ',' && !inQuote && pLevel == 0) {
                res.add(curr.trim())
                curr = ""
            } else { curr += c }
        }
        if (curr.isNotEmpty()) res.add(curr.trim())
        return res
    }

    private fun evalBasicMath(expr: String): Double {
        val sanitized = expr.replace("\\s".toRegex(), "")
        val tokens = sanitized.split("(?<=[-+*/])|(?=[-+*/])".toRegex()).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 0.0
        var result = tokens.first().toDoubleOrNull() ?: 0.0
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i]
            val nextVal = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
            when (op) {
                "+" -> result += nextVal
                "-" -> result -= nextVal
                "*" -> result *= nextVal
                "/" -> if (nextVal != 0.0) result /= nextVal
            }
            i += 2
        }
        return result
    }

    fun evaluateCondition(condStr: String): Boolean {
        val trimmed = condStr.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains(".contains{") || trimmed.contains(".contains(")) {
            val idx = if (trimmed.contains(".contains{")) trimmed.indexOf(".contains{") 
                else trimmed.indexOf(".contains(")
            val open = trimmed[idx + 9]
            val close = if (open == '{') '}' else ')'
            val base = evaluateRHS(trimmed.substring(0, idx).trim())
            val search = evaluateRHS(trimmed.substring(idx + 10, trimmed.lastIndexOf(close)).trim())
            val has = base.contains(search)
            return if (trimmed.contains("= false")) !has else has
        }
        if (trimmed.contains("=")) {
            val parts = trimmed.split("=")
            val left = evaluateRHS(parts[0].trim())
            val right = evaluateRHS(parts.drop(1).joinToString("=").trim())
            return left == right
        }
        val eval = evaluateRHS(trimmed)
        return eval == "true" || (eval.isNotEmpty() && eval != "false")
    }

    fun executeAction(actionStr: String, scope: kotlinx.coroutines.CoroutineScope) {
        val actions = mutableListOf<String>()
        var currentAct = ""
        var inQuote = false
        var pCurly = 0
        var pRound = 0
        for (char in actionStr) {
            if (char == '"' || char == '\'') inQuote = !inQuote
            if (!inQuote) {
                if (char == '{') pCurly++
                if (char == '}') pCurly--
                if (char == '(') pRound++
                if (char == ')') pRound--
            }
            if (char == ',' && !inQuote && pCurly == 0 && pRound == 0) {
                actions.add(currentAct.trim())
                currentAct = ""
            } else { currentAct += char }
        }
        if (currentAct.isNotEmpty()) actions.add(currentAct.trim())

        scope.launch {
            for (actOrig in actions) {
                var act = actOrig
                try {
                    when {
                        act.startsWith("if:") -> {
                            val p = act.indexOf('(')
                            var depth = 0
                            var condEnd = -1
                            for (i in p until act.length) {
                                if (act[i] == '(') depth++
                                if (act[i] == ')') { depth--; if (depth == 0) { condEnd = i; break } }
                            }
                            val cond = act.substring(p + 1, condEnd).trim()
                            val aStart = act.indexOf('{', condEnd)
                            var aDepth = 0
                            var aEnd = -1
                            for (i in aStart until act.length) {
                                if (act[i] == '{') aDepth++
                                if (act[i] == '}') { aDepth--; if (aDepth == 0) { aEnd = i; break } }
                            }
                            val inner = act.substring(aStart + 1, aEnd)
                            if (evaluateCondition(cond)) executeAction(inner, scope)
                        }
                        act.startsWith("wait:") -> {
                            val valStr = act.split("wait:")[1].trim()
                            val time = evaluateRHS(valStr).toFloatOrNull() ?: 0f
                            delay((time * 1000).toLong())
                        }
                        act.startsWith("add{") || act.startsWith("add(") -> {
                            val inner = act.substring(4, act.length - 1)
                            val colIdxLocal = inner.indexOf(':')
                            if (colIdxLocal > -1) {
                                val targetId = inner.substring(0, colIdxLocal).trim()
                                var compStr = inner.substring(colIdxLocal + 1).trim()
                                compStr = evaluateRHS(compStr)
                                val dynEngine = WarpEngine(compStr, state)
                                val list = state.dynamicNodes[targetId]?.toMutableList() ?: mutableListOf()
                                list.addAll(dynEngine.ast.filterIsInstance<WarpNode.Component>())
                                state.dynamicNodes[targetId] = list
                            }
                        }
                        act.startsWith("del{") || act.startsWith("del(") -> {
                            val inner = act.substring(4, act.length - 1)
                            val colIdxLocal = inner.indexOf(':')
                            val targetId = if (colIdxLocal > -1) inner.substring(0, colIdxLocal).trim() else inner.trim()
                            val list = state.dynamicNodes[targetId]?.toMutableList() ?: mutableListOf()
                            if (list.isNotEmpty()) { list.removeAt(list.size - 1); state.dynamicNodes[targetId] = list }
                        }
                        act.startsWith("clr{") || act.startsWith("clr(") -> {
                            val targetId = act.substring(4, act.length - 1).trim()
                            state.dynamicNodes[targetId] = emptyList()
                        }
                        act.startsWith("show{") || act.startsWith("show(") -> {
                            val targetId = act.substring(5, act.length - 1).trim()
                            state.visibility[targetId] = true
                        }
                        act.startsWith("hide{") || act.startsWith("hide(") -> {
                            val targetId = act.substring(5, act.length - 1).trim()
                            state.visibility[targetId] = false
                        }
                        act.startsWith("script{") || act.startsWith("script(") -> {
                            val scriptName = act.substring(7, act.length - 1).trim()
                                .removeSurrounding("'", "'").removeSurrounding("\"", "\"")
                            executeScript(scriptName, scope)
                        }
                        act.startsWith("setScreen{") || act.startsWith("setScreen(") -> {
                            val targetScreen = act.substring(10, act.length - 1).trim()
                                .removeSurrounding("'", "'").removeSurrounding("\"", "\"")
                            state.currentScreen = targetScreen
                        }
                        act.startsWith("reset{") || act.startsWith("reset(") -> {
                            state.vars.clear(); state.visibility.clear(); state.status.clear()
                            state.dynamicNodes.clear(); state.currentScreen = "main"
                            initState(ast)
                        }
                        act.contains(".setStatus{") || act.contains(".setStatus(") -> {
                            val sep = if (act.contains(".setStatus{")) ".setStatus{" else ".setStatus("
                            val targetId = act.substringBefore(sep).trim()
                            val value = act.substringAfter(sep).substringBeforeLast(
                                if (sep.endsWith("{")) "}" else ")"
                            ).trim()
                            state.status[targetId] = evaluateRHS(value)
                        }
                        act.contains(".changeContent{") || act.contains(".changeContent(") -> {
                            val sep = if (act.contains(".changeContent{")) ".changeContent{" else ".changeContent("
                            val targetId = act.substringBefore(sep).trim()
                            val value = act.substringAfter(sep).substringBeforeLast(
                                if (sep.endsWith("{")) "}" else ")"
                            ).trim()
                            state.setVar(targetId, evaluateRHS(value))
                        }
                        act.contains("=") -> {
                            val parts = act.split("=")
                            val key = parts[0].trim()
                            val valStr = parts.drop(1).joinToString("=").trim()
                            state.setVar(key, evaluateRHS(valStr))
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun executeScript(scriptName: String, scope: kotlinx.coroutines.CoroutineScope) {
        val scriptNode = ast.find { it is WarpNode.Script && it.name == scriptName } as? WarpNode.Script ?: return
        var matchedIf = false
        for (block in scriptNode.blocks) {
            if (block.type == "if") {
                matchedIf = evaluateCondition(block.condition)
                if (matchedIf) executeAction(block.actions, scope)
            } else if (block.type == "elseIf") {
                if (!matchedIf) {
                    if (evaluateCondition(block.condition)) {
                        matchedIf = true
                        executeAction(block.actions, scope)
                    }
                }
            }
        }
    }
}

// ==========================================
// Native TopAppBar Component
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeTopAppBar(
    title: String,
    actions: List<WarpNode.Component>,
    engine: WarpEngine,
    state: WarpState,
    scrollBehavior: TopAppBarScrollBehavior
) {
    TopAppBar(
        title = { Text(text = title) },
        actions = {
            actions.forEach { child ->
                WarpComponentRenderer(child, engine, state, isHeaderAction = true)
            }
        },
        scrollBehavior = scrollBehavior
    )
}

// ==========================================
// Compose UI Renderer
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpApp(code: String) {
    val context = LocalContext.current
    val state = remember { WarpState(context) }
    val engine = remember(code) { WarpEngine(code, state) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val currentScreenNode = engine.ast.filterIsInstance<WarpNode.Component>()
        .find { it.name == "screen" && engine.evalExpr(it.props["id"]) == state.currentScreen }

    if (currentScreenNode != null) {
        val headerNode = currentScreenNode.children.filterIsInstance<WarpNode.Component>()
            .find { it.name == "Header" }
        val children = currentScreenNode.children.filterIsInstance<WarpNode.Component>()
            .filter { it.name != "Header" }

        val fixedChildren = children.filter { it.props.containsKey("position") }
        val normalChildren = children.filter { !it.props.containsKey("position") }

        Scaffold(
            topBar = {
                headerNode?.let {
                    NativeTopAppBar(
                        title = engine.evalExpr(it.props["text"]),
                        actions = it.children.filterIsInstance<WarpNode.Component>(),
                        engine = engine,
                        state = state,
                        scrollBehavior = scrollBehavior
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    normalChildren.forEach { child ->
                        WarpComponentRenderer(child, engine, state)
                    }
                }
                fixedChildren.forEach { child ->
                    WarpComponentRenderer(child, engine, state)
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Screen '${state.currentScreen}' not found")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpComponentRenderer(
    node: WarpNode.Component,
    engine: WarpEngine,
    state: WarpState,
    parentModifier: Modifier = Modifier,
    isHeaderAction: Boolean = false
) {
    val idVal = engine.evalExpr(node.props["id"])
    if (idVal.isNotEmpty() && state.visibility[idVal] == false) return

    val currentStatus = if (idVal.isNotEmpty()) state.status[idVal] 
        ?: engine.evalExpr(node.props["status"]) else engine.evalExpr(node.props["status"])
    val isEnabled = currentStatus != "disabled" && !currentStatus.contains("Disabled")

    val coroutineScope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val sw = config.screenWidthDp
    val sh = config.screenHeightDp

    fun resolveViewport(s: String): String {
        return s.replace("vw", "*$sw/100").replace("vh", "*$sh/100")
    }

    val padding = engine.evalExpr(node.props["padding"]).toIntOrNull() ?: 0
    val gap = engine.evalExpr(node.props["gap"]).toIntOrNull() ?: 8
    val opacity = engine.evalExpr(node.props["opacity"]).toFloatOrNull() ?: 1f
    val bgColorStr = engine.evalExpr(node.props["background"]) ?: engine.evalExpr(node.props["color"])
    val cornerRadius = engine.evalExpr(node.props["cornerRadius"]).toIntOrNull() ?: 0
    val zIndexVal = engine.evalExpr(node.props["zIndex"]).toFloatOrNull() ?: 0f
    val widthVal = engine.evalExpr(node.props["width"])
    val heightVal = engine.evalExpr(node.props["height"])

    var modifier: Modifier = parentModifier
        .alpha(opacity)
        .zIndex(zIndexVal)
        .then(if (padding > 0) Modifier.padding(padding.dp) else Modifier)
        .then(if (bgColorStr.isNotEmpty()) Modifier.background(
            color = parseColor(bgColorStr),
            shape = RoundedCornerShape(cornerRadius.dp)
        ) else Modifier)

    val frameVal = node.props["frame"]?.joinToString("") ?: ""
    if (frameVal.isNotEmpty()) {
        frameVal.split(",").forEach { s ->
            val parts = s.split("=")
            if (parts.size == 2) {
                val k = parts[0].trim()
                val vStr = resolveViewport(parts[1].trim())
                val v = engine.evaluateRHS("calc($vStr)").replace(".0", "").toIntOrNull() ?: 0
                if (k == "width") modifier = modifier.width(v.dp)
                if (k == "height") modifier = modifier.height(v.dp)
            }
        }
    }

    val offsetVal = node.props["offset"]?.joinToString("") ?: ""
    if (offsetVal.isNotEmpty()) {
        var top = 0; var left = 0; var right = 0; var bottom = 0
        offsetVal.split(",").forEach { s ->
            val parts = s.split("=")
            if (parts.size == 2) {
                val k = parts[0].trim()
                val vStr = resolveViewport(parts[1].trim())
                val v = engine.evaluateRHS("calc($vStr)").replace(".0", "").toIntOrNull() ?: 0
                when (k) { "top" -> top = v; "left" -> left = v; "right" -> right = v; "bottom" -> bottom = v }
            }
        }
        modifier = modifier.padding(start = left.dp, top = top.dp, end = right.dp, bottom = bottom.dp)
    }

    val positionVal = node.props["position"]?.joinToString("") ?: ""
    if (positionVal.isNotEmpty()) {
        var top: Int? = null; var left: Int? = null; var right: Int? = null; var bottom: Int? = null
        positionVal.split(",").forEach { s ->
            val parts = s.split("=")
            if (parts.size == 2) {
                val k = parts[0].trim()
                val vStr = resolveViewport(parts[1].trim())
                val v = engine.evaluateRHS("calc($vStr)").replace(".0", "").toIntOrNull() ?: 0
                when (k) { "top" -> top = v; "bottom" -> bottom = v; "left" -> left = v; "right" -> right = v }
            }
        }
        if (top != null) modifier = modifier.offset(y = top!!.dp)
        if (bottom != null) modifier = modifier.offset(y = (sh - bottom!! - 40).dp)
        if (left != null) modifier = modifier.offset(x = left!!.dp)
        if (right != null) modifier = modifier.offset(x = (sw - right!! - 100).dp)
    }

    if (widthVal == "max") modifier = modifier.fillMaxWidth()
    else if (widthVal.isNotEmpty()) {
        val wStr = resolveViewport(widthVal)
        val w = if (wStr.contains("*")) engine.evaluateRHS("calc($wStr)").replace(".0", "").toIntOrNull() 
            else wStr.toIntOrNull()
        if (w != null) modifier = modifier.width(w.dp)
    }

    if (heightVal == "max") modifier = modifier.fillMaxHeight()
    else if (heightVal.isNotEmpty()) {
        val hStr = resolveViewport(heightVal)
        val h = if (hStr.contains("*")) engine.evaluateRHS("calc($hStr)").replace(".0", "").toIntOrNull() 
            else hStr.toIntOrNull()
        if (h != null) modifier = modifier.height(h.dp)
    }

    when (node.name) {
        "if" -> {
            val condition = engine.evalExpr(node.props["condition"])
            if (engine.evaluateCondition(condition)) {
                node.children.forEach { child ->
                    if (child is WarpNode.Component) WarpComponentRenderer(child, engine, state)
                }
            }
        }
        "scrollView" -> {
            Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                node.children.forEach { child ->
                    if (child is WarpNode.Component) {
                        val childWidth = engine.evalExpr(child.props["width"])
                        val childModifier = if (childWidth == "max") Modifier.fillMaxWidth() else Modifier
                        WarpComponentRenderer(child, engine, state, childModifier)
                    }
                }
            }
        }
        "vStack" -> {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap.dp)) {
                node.children.forEach { child ->
                    if (child is WarpNode.Component) {
                        val childHeight = engine.evalExpr(child.props["height"])
                        val childWidth = engine.evalExpr(child.props["width"])
                        var childModifier: Modifier = Modifier
                        if (childHeight == "max") childModifier = childModifier.weight(1f)
                        if (childWidth == "max") childModifier = childModifier.fillMaxWidth()
                        WarpComponentRenderer(child, engine, state, childModifier)
                    }
                }
            }
        }
        "hStack" -> {
            Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap.dp), 
                verticalAlignment = Alignment.CenterVertically) {
                node.children.forEach { child ->
                    if (child is WarpNode.Component) {
                        val childWidth = engine.evalExpr(child.props["width"])
                        val childHeight = engine.evalExpr(child.props["height"])
                        var childModifier: Modifier = Modifier
                        if (childWidth == "max") childModifier = childModifier.weight(1f)
                        if (childHeight == "max") childModifier = childModifier.fillMaxHeight()
                        WarpComponentRenderer(child, engine, state, childModifier)
                    }
                }
            }
        }
        "text" -> {
            val colorVal = engine.evalExpr(node.props["color"]) ?: engine.evalExpr(node.props["textColor"])
            val fontSize = engine.evalExpr(node.props["fontSize"]).toIntOrNull() ?: 16
            val fontWeightStr = engine.evalExpr(node.props["fontWeight"])
            val alignStr = engine.evalExpr(node.props["align"])
            Text(
                text = engine.evalExpr(node.props["text"]),
                color = parseColor(colorVal),
                fontSize = fontSize.sp,
                fontWeight = if (fontWeightStr == "bold") FontWeight.Bold else FontWeight.Normal,
                textAlign = when (alignStr) { "center" -> TextAlign.Center; "trailing" -> TextAlign.End; else -> TextAlign.Start },
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
        "card" -> {
            OutlinedCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val title = engine.evalExpr(node.props["text"])
                    if (title.isNotEmpty()) Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    node.children.forEach { child ->
                        if (child is WarpNode.Component) {
                            val childHeight = engine.evalExpr(child.props["height"])
                            val childModifier = if (childHeight == "max") Modifier.weight(1f) else Modifier
                            WarpComponentRenderer(child, engine, state, childModifier)
                        }
                    }
                }
            }
        }
        "button", "tonalButton" -> {
            val text = engine.evalExpr(node.props["text"])
            val isTonal = node.name == "tonalButton" || engine.evalExpr(node.props["tonalButton"]) == "true"
            val onClick = { node.events["oneClick"]?.let { engine.executeAction(it, coroutineScope) }; Unit }
            val onLongClick = { node.events["longPress"]?.let { engine.executeAction(it, coroutineScope) }; Unit }
            when {
                isHeaderAction -> {
                    TextButton(
                        onClick = onClick, enabled = isEnabled,
                        modifier = modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { if(isEnabled) onLongClick() },
                                onTap = { if(isEnabled) onClick() }
                            )
                        }
                    ) { Text(text) }
                }
                isTonal -> {
                    FilledTonalButton(
                        onClick = onClick, enabled = isEnabled,
                        modifier = modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { if(isEnabled) onLongClick() },
                                onTap = { if(isEnabled) onClick() }
                            )
                        }
                    ) { Text(text) }
                }
                else -> {
                    Button(
                        onClick = onClick, enabled = isEnabled,
                        modifier = modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { if(isEnabled) onLongClick() },
                                onTap = { if(isEnabled) onClick() }
                            )
                        }
                    ) { Text(text) }
                }
            }
        }
        "textField", "input" -> {
            val varName = node.props["text"]?.joinToString("") ?: node.events["output"] ?: ""
            val textValue = state.vars[varName] ?: state.getVar(varName)
            OutlinedTextField(
                value = textValue, enabled = isEnabled,
                onValueChange = { state.setVar(varName, it) },
                placeholder = { Text(engine.evalExpr(node.props["placeholder"])) },
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
        "toggle", "switch" -> {
            val varName = node.props["isOn"]?.joinToString("") ?: node.events["output"] ?: ""
            val checked = (state.vars[varName] ?: state.getVar(varName)).contains("true")
            Switch(
                checked = checked, enabled = isEnabled,
                onCheckedChange = { state.setVar(varName, it.toString()) },
                modifier = modifier
            )
        }
        "slider" -> {
            val varName = node.props["value"]?.joinToString("") ?: node.events["output"] ?: ""
            val min = engine.evalExpr(node.props["min"]).toFloatOrNull() ?: 0f
            val max = engine.evalExpr(node.props["max"]).toFloatOrNull() ?: 100f
            val value = (state.vars[varName] ?: state.getVar(varName)).toFloatOrNull() ?: min
            Slider(
                value = value, enabled = isEnabled,
                onValueChange = { state.setVar(varName, it.toString()) },
                valueRange = min..max,
                modifier = modifier.fillMaxWidth()
            )
        }
        "divider" -> {
            HorizontalDivider(modifier = modifier.padding(vertical = 8.dp))
        }
    }

    if (idVal.isNotEmpty()) {
        state.dynamicNodes[idVal]?.forEach { dNode ->
            WarpComponentRenderer(dNode, engine, state)
        }
    }
}

fun parseColor(colorStr: String): Color {
    return when (colorStr) {
        "yellow" -> Color(0xFFFBC02D)
        "red" -> Color(0xFFB3261E)
        "blue" -> Color(0xFF0A56D0)
        "gray" -> Color(0xFF808080)
        "black" -> Color(0xFF000000)
        "white" -> Color(0xFFFFFFFF)
        else -> if (colorStr.startsWith("#")) {
            try { Color(android.graphics.Color.parseColor(colorStr)) } catch (e: Exception) { Color.Unspecified }
        } else Color.Unspecified
    }
}

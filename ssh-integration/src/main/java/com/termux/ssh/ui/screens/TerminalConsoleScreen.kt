package com.termux.ssh.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.termux.ssh.GeminiService
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.ssh.data.CommandHistoryEntity
import com.termux.ssh.data.ServerEntity
import com.termux.ssh.SSHConsoleLine
import com.termux.ssh.TerminalSession
import com.termux.ssh.TerminalViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun formatSpeedString(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024 -> String.format("%.1f KB/s", bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}

fun formatSizeString(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalConsoleScreen(
    initialServerId: Long,
    viewModel: TerminalViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val activeSessions = viewModel.activeSessions

    // Track active server tab (default to initialServerId)
    var currentServerId by remember { mutableLongStateOf(initialServerId) }
    
    // Set active server id in viewmodel
    LaunchedEffect(currentServerId) {
        viewModel.activeServerId = currentServerId
    }

    val activeSession = activeSessions[currentServerId]
    val isConnected = activeSession?.isConnected ?: false

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    val allLogs = activeSession?.lines?.joinToString("\n") { it.text } ?: ""
                    outputStream.write(allLogs.toByteArray())
                }
                Toast.makeText(context, "Session logs exported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export logs: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Fetch command history
    val history by viewModel.getHistoryForServer(currentServerId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // UI Input field state
    var commandInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // App Modifier Key States (Ctrl, Alt, Shift toggles)
    var isCtrlPressed by remember { mutableStateOf(false) }
    var isAltPressed by remember { mutableStateOf(false) }
    var isShiftPressed by remember { mutableStateOf(false) }

    // History Search Dialog state
    var showHistoryDialog by remember { mutableStateOf(false) }

    // Selectable Log Dialog state
    var showSelectableLogDialog by remember { mutableStateOf(false) }

    // Tab context menu state
    var tabContextMenuId by remember { mutableStateOf<Long?>(null) }

    // Dropdown to open another server tab
    var expandedServerDropdown by remember { mutableStateOf(false) }

    // SFTP File Manager Dialog state
    var showSftpDialog by remember { mutableStateOf(false) }

    // Gemini Copilot state
    var showGeminiCopilot by remember { mutableStateOf(false) }
    var geminiQuery by remember { mutableStateOf("") }
    var geminiResponse by remember { mutableStateOf("") }
    var geminiLoading by remember { mutableStateOf(false) }
    var suggestedCommands by remember { mutableStateOf<List<String>>(emptyList()) }

    // Top bar 3-dots menu & Top Overlay states
    var showTopMenu by remember { mutableStateOf(false) }
    var showTopOverlay by remember { mutableStateOf(false) }

    // Live network speed calculation ticker (updates rxSpeedBytesPerSec and txSpeedBytesPerSec every second)
    LaunchedEffect(activeSession) {
        var lastRx = activeSession?.rxBytes ?: 0L
        var lastTx = activeSession?.txBytes ?: 0L
        while (true) {
            delay(1000L)
            activeSession?.let { sess ->
                val curRx = sess.rxBytes
                val curTx = sess.txBytes
                sess.rxSpeedBytesPerSec = (curRx - lastRx).coerceAtLeast(0L)
                sess.txSpeedBytesPerSec = (curTx - lastTx).coerceAtLeast(0L)
                lastRx = curRx
                lastTx = curTx
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = activeSession?.serverName ?: "SSH Terminal",
                            color = Color(0xFFE2E2E6),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color(0xFFE2E2E6)
                            )
                        }
                    },
                    actions = {
                        // Up & Down connection speed link badge next to 3-dots
                        val rxSpeedStr = formatSpeedString(activeSession?.rxSpeedBytesPerSec ?: 0L)
                        val txSpeedStr = formatSpeedString(activeSession?.txSpeedBytesPerSec ?: 0L)

                        Surface(
                            onClick = { showTopOverlay = !showTopOverlay },
                            shape = RoundedCornerShape(16.dp),
                            color = if (showTopOverlay) Color(0xFF2A3C34) else Color(0xFF22252A),
                            border = BorderStroke(1.dp, if (showTopOverlay) Color(0xFF34D399) else Color(0xFF44474E)),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .testTag("top_overlay_toggle_btn")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("▲ $txSpeedStr", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("▼ $rxSpeedStr", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Icon(
                                    imageVector = if (showTopOverlay) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Toggle Speed Overlay",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        // 3-Dots menu hiding all top tools/symbols
                        IconButton(
                            onClick = { showTopMenu = true },
                            modifier = Modifier.testTag("top_3dots_menu_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = Color(0xFFE2E2E6)
                            )
                        }

                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                            modifier = Modifier.background(Color(0xFF2A2D35))
                        ) {
                            DropdownMenuItem(
                                text = { Text("SFTP File Explorer", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFFD0BCFF)) },
                                onClick = {
                                    showTopMenu = false
                                    showSftpDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Gemini AI Copilot", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFD0BCFF)) },
                                onClick = {
                                    showTopMenu = false
                                    showGeminiCopilot = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Select & Copy Logs", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null, tint = Color(0xFFD0BCFF)) },
                                onClick = {
                                    showTopMenu = false
                                    showSelectableLogDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Command History", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFFD0BCFF)) },
                                onClick = {
                                    showTopMenu = false
                                    showHistoryDialog = true
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1C1E))
                )

                // Top Up & Down Connection Speed Overlay (Collapsible)
                if (showTopOverlay) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF22252A))
                            .border(1.dp, Color(0xFF44474E))
                            .padding(12.dp),
                        shape = RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF22252A))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Header row with title & close button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                                    Text("Connection Speed & Traffic Stats", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = { showTopOverlay = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Close Overlay", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }

                            // Upload & Download stats boxes
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Upload Box
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(0xFF1A1C1E), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF2E4034), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Upload, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(14.dp))
                                            Text("UPLOAD (UP)", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = formatSpeedString(activeSession?.txSpeedBytesPerSec ?: 0L),
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Total: ${formatSizeString(activeSession?.txBytes ?: 0L)}",
                                            color = Color.Gray,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                // Download Box
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(0xFF1A1C1E), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(14.dp))
                                            Text("DOWNLOAD (DOWN)", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = formatSpeedString(activeSession?.rxSpeedBytesPerSec ?: 0L),
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "Total: ${formatSizeString(activeSession?.rxBytes ?: 0L)}",
                                            color = Color.Gray,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Status: ${if (isConnected) "Active Shell (xterm)" else "Disconnected"}",
                                    color = if (isConnected) Color(0xFF34D399) else Color(0xFFFF5252),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Session Server ID: $currentServerId",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                // Multi-session tabs row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1C1E))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Session tabs list
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        activeSessions.keys.forEach { sId ->
                            val sess = activeSessions[sId]
                            val isSelected = sId == currentServerId
                            
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (isSelected) Color(0xFFD0BCFF).copy(alpha = 0.15f) else Color(0xFF2A2D35),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (sess?.isConnected == true) Color(0xFF34D399) else Color(0xFF44474E),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .combinedClickable(
                                        onClick = { currentServerId = sId },
                                        onLongClick = { tabContextMenuId = sId }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = sess?.serverName ?: "Sess $sId",
                                    color = if (isSelected) Color(0xFFD0BCFF) else Color(0xFFC2C7CF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                DropdownMenu(
                                    expanded = (tabContextMenuId == sId),
                                    onDismissRequest = { tabContextMenuId = null },
                                    modifier = Modifier.background(Color(0xFF2A2D35))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Close Terminal / Disconnect", color = Color(0xFFFF5252)) },
                                        leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, tint = Color(0xFFFF5252)) },
                                        onClick = {
                                            tabContextMenuId = null
                                            viewModel.disconnectServer(sId)
                                            activeSessions.remove(sId)
                                            if (currentServerId == sId) {
                                                val remaining = activeSessions.keys.firstOrNull()
                                                if (remaining != null) {
                                                    currentServerId = remaining
                                                } else {
                                                    onBack()
                                                }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Reconnect Session", color = Color(0xFF34D399)) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF34D399)) },
                                        onClick = {
                                            tabContextMenuId = null
                                            viewModel.connectToServer(sId)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear Output Logs", color = Color.White) },
                                        leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null, tint = Color.White) },
                                        onClick = {
                                            tabContextMenuId = null
                                            sess?.clearConsole()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Add new tab button
                    IconButton(
                        onClick = { expandedServerDropdown = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("add_session_tab_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Open Session", tint = Color(0xFFD0BCFF))
                    }

                    DropdownMenu(
                        expanded = expandedServerDropdown,
                        onDismissRequest = { expandedServerDropdown = false },
                        modifier = Modifier.background(Color(0xFF2A2D35))
                    ) {
                        DropdownMenuItem(
                            text = { Text("💻 New Local Terminal", color = Color(0xFF34D399), fontWeight = FontWeight.Bold) },
                            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF34D399)) },
                            onClick = {
                                val localId = viewModel.createLocalTerminalSession()
                                currentServerId = localId
                                expandedServerDropdown = false
                            }
                        )
                        HorizontalDivider(color = Color(0xFF44474E))
                        servers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text(server.name, color = Color.White) },
                                onClick = {
                                    viewModel.connectToServer(server.id)
                                    currentServerId = server.id
                                    expandedServerDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFF1A1C1E)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Live Terminal Console Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF000000))
                    .padding(8.dp)
            ) {
                if (activeSession != null) {
                    val listState = rememberLazyListState()
                    val terminalLines = activeSession.lines
                    val currentLine = activeSession.currentLineText
                    val fontScale = activeSession.fontScale

                    // Auto scroll to bottom when lines arrive
                    LaunchedEffect(terminalLines.size, currentLine) {
                        if (terminalLines.isNotEmpty()) {
                            listState.animateScrollToItem(terminalLines.size)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("console_output_list")
                    ) {
                        items(terminalLines) { line ->
                            ConsoleLineItem(
                                line = line,
                                fontScale = fontScale,
                                context = context
                            )
                        }
                        
                        // Active prompt line
                        if (currentLine.isNotEmpty()) {
                            item {
                                ConsoleLineItem(
                                    line = SSHConsoleLine(currentLine, SSHConsoleLine.LineType.OUTPUT),
                                    fontScale = fontScale,
                                    context = context
                                )
                            }
                        }
                    }

                    // Zooming / Clear Console float panel
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color(0xCC2A2D35), RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { activeSession.fontScale = (activeSession.fontScale - 1f).coerceAtLeast(8f) },
                            modifier = Modifier.size(32.dp).testTag("zoom_out_btn")
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = "${fontScale.toInt()}sp",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { activeSession.fontScale = (activeSession.fontScale + 1f).coerceAtMost(32f) },
                            modifier = Modifier.size(32.dp).testTag("zoom_in_btn")
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { activeSession.clearConsole() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Console", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                        }
                    }

                    // Status Overlay for Reconnecting / Connecting
                    if (activeSession.isReconnecting) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xEEFFB300))
                                .padding(8.dp)
                                .align(Alignment.BottomCenter),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connection lost. Auto-reconnecting...", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFFD0BCFF))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Initializing connection shell...", color = Color.Gray)
                        }
                    }
                }
            }

            // Soft Keyboard / Input Focus State for Auto Complete & Symbols Toolbar
            var isInputFocused by remember { mutableStateOf(false) }
            val showKeyboardToolbars = isInputFocused && isConnected

            if (showKeyboardToolbars) {
                // Suggestions / Auto Complete Row based on History & Defaults
                val suggestions = if (commandInput.isEmpty()) {
                    // Return default often used shell commands if input is empty
                    listOf("ls -la", "cd ..", "git status", "top", "df -h", "htop", "docker ps", "uname -a")
                } else {
                    // Filter matching commands from history
                    history.map { it.command }
                        .distinct()
                        .filter { it.startsWith(commandInput, ignoreCase = true) && it != commandInput }
                        .take(5)
                }

                if (suggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1C1E))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { suggestion ->
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF2A2D35), RoundedCornerShape(4.dp))
                                    .clickable {
                                        commandInput = suggestion
                                        focusRequester.requestFocus()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(suggestion, color = Color(0xFFD0BCFF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                // Custom Programmer Modifier Keyboard Bar
                CustomProgrammerKeyboard(
                    serverId = currentServerId,
                    isCtrlPressed = isCtrlPressed,
                    onToggleCtrl = { isCtrlPressed = !isCtrlPressed },
                    isAltPressed = isAltPressed,
                    onToggleAlt = { isAltPressed = !isAltPressed },
                    isShiftPressed = isShiftPressed,
                    onToggleShift = { isShiftPressed = !isShiftPressed },
                    onSendRaw = { bytes -> viewModel.sendRawBytes(currentServerId, bytes) },
                    onInsertText = { text ->
                        commandInput += text
                        focusRequester.requestFocus()
                    },
                    onClearInput = { commandInput = "" },
                    onPasteClipboard = {
                        val pasted = viewModel.readFromClipboard(context)
                        if (pasted.isNotEmpty()) {
                            commandInput += pasted
                        }
                    }
                )
            }

            // Command Input TextField Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1C1E))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { newInput ->
                        if (isCtrlPressed || isAltPressed || isShiftPressed) {
                            if (newInput.length > commandInput.length) {
                                val addedChar = newInput.last()
                                if (isCtrlPressed) {
                                    val ctrlByte = getControlByte(addedChar)
                                    if (ctrlByte != null) {
                                        viewModel.sendRawBytes(currentServerId, byteArrayOf(ctrlByte))
                                    } else {
                                        viewModel.sendRawBytes(currentServerId, addedChar.toString().toByteArray())
                                    }
                                    isCtrlPressed = false
                                } else if (isAltPressed) {
                                    val altBytes = byteArrayOf(27, addedChar.code.toByte())
                                    viewModel.sendRawBytes(currentServerId, altBytes)
                                    isAltPressed = false
                                } else if (isShiftPressed) {
                                    val shiftedChar = addedChar.uppercaseChar().toString()
                                    commandInput = commandInput + shiftedChar
                                    isShiftPressed = false
                                }
                            } else {
                                commandInput = newInput
                            }
                        } else {
                            commandInput = newInput
                        }
                    },
                    placeholder = { Text("Enter command...", color = Color(0xFFC2C7CF).copy(alpha = 0.6f), fontSize = 14.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color(0xFF44474E),
                        focusedContainerColor = Color(0xFF2A2D35),
                        unfocusedContainerColor = Color(0xFF2A2D35)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { isInputFocused = it.isFocused }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val hwCtrl = keyEvent.isCtrlPressed
                                val hwAlt = keyEvent.isAltPressed

                                val ctrlActive = isCtrlPressed || hwCtrl
                                val altActive = isAltPressed || hwAlt

                                val unicodeChar = keyEvent.nativeKeyEvent.unicodeChar

                                if (ctrlActive) {
                                    val char = unicodeChar.toChar()
                                    val ctrlByte = if (unicodeChar in 1..127) getControlByte(char) else null
                                    val finalByte = ctrlByte ?: getControlByteFromKey(keyEvent.key)
                                    if (finalByte != null) {
                                        viewModel.sendRawBytes(currentServerId, byteArrayOf(finalByte))
                                        isCtrlPressed = false
                                        isAltPressed = false
                                        isShiftPressed = false
                                        true
                                    } else {
                                        false
                                    }
                                } else if (altActive) {
                                    val char = unicodeChar.toChar()
                                    if (unicodeChar in 1..127) {
                                        viewModel.sendRawBytes(currentServerId, byteArrayOf(27, char.code.toByte()))
                                        isCtrlPressed = false
                                        isAltPressed = false
                                        isShiftPressed = false
                                        true
                                    } else {
                                        false
                                    }
                                } else if (isShiftPressed) {
                                    val char = unicodeChar.toChar()
                                    if (unicodeChar in 1..127) {
                                        commandInput += char.uppercaseChar().toString()
                                        isShiftPressed = false
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        }
                        .testTag("command_input_field"),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (commandInput.isNotBlank() && isConnected) {
                                viewModel.sendCommand(currentServerId, commandInput)
                                commandInput = ""
                            }
                        }
                    ),
                    enabled = isConnected
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (commandInput.isNotBlank() && isConnected) {
                            viewModel.sendCommand(currentServerId, commandInput)
                            commandInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD0BCFF),
                        disabledContainerColor = Color(0xFF44474E)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    enabled = commandInput.isNotBlank() && isConnected,
                    modifier = Modifier.testTag("send_command_btn")
                ) {
                    Text("Send", color = if (isConnected) Color(0xFF1A1C1E) else Color.LightGray)
                }
            }
        }

        // History Dialog Search List
        if (showHistoryDialog) {
            var searchHistoryQuery by remember { mutableStateOf("") }
            val filteredHistory = history.filter {
                it.command.contains(searchHistoryQuery, ignoreCase = true)
            }

            AlertDialog(
                onDismissRequest = { showHistoryDialog = false },
                containerColor = Color(0xFF2A2D35),
                title = { Text("Command History Search", color = Color.White) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = searchHistoryQuery,
                            onValueChange = { searchHistoryQuery = it },
                            placeholder = { Text("Search past commands...", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color(0xFF44474E)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                        ) {
                            if (filteredHistory.isEmpty()) {
                                item {
                                    Text("No matching command history.", color = Color.Gray, modifier = Modifier.padding(16.dp))
                                }
                            } else {
                                items(filteredHistory) { hist ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                commandInput = hist.command
                                                showHistoryDialog = false
                                                focusRequester.requestFocus()
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            hist.command,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            "x${hist.useCount}",
                                            color = Color(0xFFD0BCFF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    HorizontalDivider(color = Color(0xFF44474E))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showHistoryDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF))
                    ) {
                        Text("Close", color = Color(0xFF1A1C1E))
                    }
                }
            )
        }

        // Full Screen Selectable Logs Dialog
        if (showSelectableLogDialog) {
            val allLogs = activeSession?.lines?.joinToString("\n") { it.text } ?: "No logs active."

            AlertDialog(
                onDismissRequest = { showSelectableLogDialog = false },
                containerColor = Color(0xFF2A2D35),
                title = { Text("Select & Copy Terminal Session Logs", color = Color.White, fontSize = 16.sp) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Highlight or select text anywhere below to copy directly to your clipboard.", color = Color.LightGray, fontSize = 12.sp)
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .background(Color(0xFF000000), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF44474E), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            SelectionContainer {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Text(
                                            text = allLogs,
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(
                                onClick = {
                                    viewModel.copyToClipboard(context, "Terminal Logs", allLogs)
                                    Toast.makeText(context, "Full logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1C1E)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy All", tint = Color(0xFFD0BCFF))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copy Entire Session Logs", color = Color.White)
                            }

                            Button(
                                onClick = {
                                    val sanitizedName = activeSession?.serverName?.replace("\\s+".toRegex(), "_") ?: "ssh_session"
                                    val defaultFileName = "${sanitizedName}_log.txt"
                                    exportLauncher.launch(defaultFileName)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1C1E)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Export to File", tint = Color(0xFFD0BCFF))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Export Logs to Local File", color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSelectableLogDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF))
                    ) {
                        Text("Done", color = Color(0xFF1A1C1E))
                    }
                }
            )
        }

        // Gemini AI Copilot Dialog
        if (showGeminiCopilot) {
            AlertDialog(
                onDismissRequest = { showGeminiCopilot = false },
                containerColor = Color(0xFF2A2D35),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Gemini",
                            tint = Color(0xFFD0BCFF)
                        )
                        Text("Gemini AI Terminal Copilot", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Get smart suggestions, command explanations, or automate local/remote operations using Gemini 3.5 Flash.",
                            color = Color(0xFFC2C7CF),
                            fontSize = 12.sp
                        )

                        // Quick action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        geminiLoading = true
                                        geminiResponse = ""
                                        suggestedCommands = emptyList()
                                        
                                        val logsContext = activeSession?.lines?.takeLast(30)?.joinToString("\n") { it.text } ?: ""
                                        val prompt = "Recent Terminal Output:\n$logsContext\n\nAnalyze the recent terminal output, explain any errors or status, and suggest standard Linux commands to proceed or fix errors. Put commands in markdown code blocks."
                                        val systemPrompt = "You are a senior Linux DevOps engineer assisting with shell commands. Always enclose suggested commands in markdown code blocks, like ```bash\ncommand\n```."
                                        
                                        val response = GeminiService.getAiResponse(prompt, systemPrompt)
                                        if (response == "API_KEY_MISSING") {
                                            geminiResponse = "API_KEY_MISSING"
                                        } else {
                                            geminiResponse = response
                                            suggestedCommands = GeminiService.extractCommands(response)
                                        }
                                        geminiLoading = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1C1E)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                enabled = !geminiLoading
                            ) {
                                Text("Analyze Output", fontSize = 12.sp, color = Color(0xFFD0BCFF))
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        geminiLoading = true
                                        geminiResponse = ""
                                        suggestedCommands = emptyList()
                                        
                                        val logsContext = activeSession?.lines?.takeLast(15)?.joinToString("\n") { it.text } ?: ""
                                        val prompt = "Recent Terminal Output:\n$logsContext\n\nSuggest 3 common Linux troubleshooting or monitoring commands (such as htop, df -h, systemctl status, etc.) suitable for checking the system health right now. Put commands in markdown code blocks."
                                        val systemPrompt = "You are a senior Linux DevOps engineer assisting with shell commands. Always enclose suggested commands in markdown code blocks, like ```bash\ncommand\n```."
                                        
                                        val response = GeminiService.getAiResponse(prompt, systemPrompt)
                                        if (response == "API_KEY_MISSING") {
                                            geminiResponse = "API_KEY_MISSING"
                                        } else {
                                            geminiResponse = response
                                            suggestedCommands = GeminiService.extractCommands(response)
                                        }
                                        geminiLoading = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1C1E)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                enabled = !geminiLoading
                            ) {
                                Text("Suggest Tools", fontSize = 12.sp, color = Color(0xFFD0BCFF))
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF44474E)))

                        // Custom Query input
                        OutlinedTextField(
                            value = geminiQuery,
                            onValueChange = { geminiQuery = it },
                            placeholder = { Text("Ask Gemini anything (e.g. how to search text inside files)", color = Color.Gray, fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color(0xFF44474E)
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp),
                            trailingIcon = {
                                if (geminiQuery.isNotBlank() && !geminiLoading) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                geminiLoading = true
                                                geminiResponse = ""
                                                suggestedCommands = emptyList()
                                                
                                                val logsContext = activeSession?.lines?.takeLast(15)?.joinToString("\n") { it.text } ?: ""
                                                val prompt = "The user asks: \"$geminiQuery\"\n\nRecent Terminal Context:\n$logsContext\n\nProvide clear instructions and matching Linux commands. Always enclose suggested terminal commands in markdown code blocks."
                                                val systemPrompt = "You are a senior Linux systems administrator assisting a developer. Always enclose suggested commands in markdown code blocks, like ```bash\ncommand\n```."
                                                
                                                val response = GeminiService.getAiResponse(prompt, systemPrompt)
                                                if (response == "API_KEY_MISSING") {
                                                    geminiResponse = "API_KEY_MISSING"
                                                } else {
                                                    geminiResponse = response
                                                    suggestedCommands = GeminiService.extractCommands(response)
                                                    geminiQuery = ""
                                                }
                                                geminiLoading = false
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = "Ask Gemini", tint = Color(0xFFD0BCFF))
                                    }
                                }
                            },
                            singleLine = true
                        )

                        // Loading or Response Area
                        if (geminiLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFFD0BCFF))
                            }
                        } else if (geminiResponse.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(Color(0xFF1A1C1E), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF44474E), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                item {
                                    if (geminiResponse == "API_KEY_MISSING") {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = "Gemini API Key is Missing!",
                                                color = Color(0xFFFF5252),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Please configure your GEMINI_API_KEY inside the Secrets panel of the Google AI Studio UI to enable AI features.",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = geminiResponse,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.SansSerif
                                        )

                                        if (suggestedCommands.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "Detected Shell Commands:",
                                                color = Color(0xFFD0BCFF),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            
                                            suggestedCommands.forEach { cmd ->
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color(0xFF2A3A4A), RoundedCornerShape(6.dp))
                                                        .border(1.dp, Color(0xFF44474E), RoundedCornerShape(6.dp))
                                                        .padding(8.dp)
                                                ) {
                                                    Text(
                                                        text = cmd,
                                                        color = Color(0xFF34D399),
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.End,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        TextButton(
                                                            onClick = {
                                                                commandInput = cmd
                                                                showGeminiCopilot = false
                                                            }
                                                        ) {
                                                            Text("Use Command", color = Color(0xFFD0BCFF), fontSize = 11.sp)
                                                        }
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Button(
                                                            onClick = {
                                                                if (isConnected) {
                                                                    viewModel.sendCommand(currentServerId, cmd)
                                                                    Toast.makeText(context, "Executing suggested command...", Toast.LENGTH_SHORT).show()
                                                                    showGeminiCopilot = false
                                                                } else {
                                                                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_SHORT).show()
                                                                }
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF)),
                                                            shape = RoundedCornerShape(4.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("Run Now", color = Color(0xFF1A1C1E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGeminiCopilot = false }) {
                        Text("Close", color = Color(0xFFD0BCFF))
                    }
                }
            )
        }

        // SFTP File Manager Dialog
        if (showSftpDialog) {
            val currentServerEntity = servers.find { it.id == currentServerId }
            if (currentServerEntity != null) {
                SftpFileManagerDialog(
                    server = currentServerEntity,
                    onDismiss = { showSftpDialog = false }
                )
            }
        }
    }
}

@Composable
fun ConsoleLineItem(
    line: SSHConsoleLine,
    fontScale: Float,
    context: android.content.Context
) {
    val textColor = when (line.type) {
        SSHConsoleLine.LineType.COMMAND -> Color(0xFF7DBA7D) // user command soft green
        SSHConsoleLine.LineType.PROMPT -> Color(0xFF7D7DBA) // prompt blue-purple
        SSHConsoleLine.LineType.ERROR -> Color(0xFFFF5252) // bright red
        SSHConsoleLine.LineType.SYSTEM -> Color(0xFFD0BCFF) // system detail lavender
        SSHConsoleLine.LineType.OUTPUT -> Color(0xFFC2C7CF) // output light grey
    }

    val annotatedString = parseTextWithLinks(line.text)

    ClickableText(
        text = annotatedString,
        style = TextStyle(
            color = textColor,
            fontSize = fontScale.sp,
            fontFamily = FontFamily.Monospace
        ),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                        context.startActivity(browserIntent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open browser link", Toast.LENGTH_SHORT).show()
                    }
                }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}

// Regex link parser
fun parseTextWithLinks(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val urlPattern = "(https?://[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/[^\\s]*)?)".toRegex()
    val matches = urlPattern.findAll(text)
    
    var lastIndex = 0
    for (match in matches) {
        val start = match.range.first
        val end = match.range.last + 1
        
        // Append text before link
        if (start > lastIndex) {
            builder.append(text.substring(lastIndex, start))
        }
        
        // Append styled link
        builder.pushStringAnnotation(tag = "URL", annotation = match.value)
        builder.pushStyle(
            SpanStyle(
                color = Color(0xFF00D4FF),
                textDecoration = TextDecoration.Underline
            )
        )
        builder.append(match.value)
        builder.pop()
        builder.pop()
        
        lastIndex = end
    }
    
    if (lastIndex < text.length) {
        builder.append(text.substring(lastIndex))
    }
    
    return builder.toAnnotatedString()
}

fun getControlByte(ch: Char): Byte? {
    val upper = ch.uppercaseChar()
    return when {
        upper in 'A'..'Z' -> (upper.code - '@'.code).toByte()
        ch == '[' -> 27
        ch == '\\' -> 28
        ch == ']' -> 29
        ch == '^' -> 30
        ch == '_' -> 31
        ch == '?' -> 127
        ch == ' ' -> 0
        else -> null
    }
}

fun getControlByteFromKey(key: Key): Byte? {
    return when (key) {
        Key.A -> 1
        Key.B -> 2
        Key.C -> 3
        Key.D -> 4
        Key.E -> 5
        Key.F -> 6
        Key.G -> 7
        Key.H -> 8
        Key.I -> 9
        Key.J -> 10
        Key.K -> 11
        Key.L -> 12
        Key.M -> 13
        Key.N -> 14
        Key.O -> 15
        Key.P -> 16
        Key.Q -> 17
        Key.R -> 18
        Key.S -> 19
        Key.T -> 20
        Key.U -> 21
        Key.V -> 22
        Key.W -> 23
        Key.X -> 24
        Key.Y -> 25
        Key.Z -> 26
        Key.LeftBracket -> 27
        Key.Backslash -> 28
        Key.RightBracket -> 29
        else -> null
    }
}

@Composable
fun CustomProgrammerKeyboard(
    serverId: Long,
    isCtrlPressed: Boolean,
    onToggleCtrl: () -> Unit,
    isAltPressed: Boolean,
    onToggleAlt: () -> Unit,
    isShiftPressed: Boolean,
    onToggleShift: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onInsertText: (String) -> Unit,
    onClearInput: () -> Unit,
    onPasteClipboard: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("MODS") } // MODS, NAV, SYM, MACROS

    // Customizable user macros
    val customMacros = remember {
        mutableStateListOf(
            "htop", "git status", "ls -la", "docker ps", "sudo su", "clear", "ping 8.8.8.8", "systemctl status"
        )
    }

    var showAddMacroDialog by remember { mutableStateOf(false) }
    var newMacroText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2D35))
            .border(width = 1.dp, color = Color(0xFF44474E))
    ) {
        // Section Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category switch tabs
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "MODS" to "Modifiers",
                    "NAV" to "Nav & Keys",
                    "SYM" to "Symbols",
                    "MACROS" to "Macros & Cmds"
                ).forEach { (code, label) ->
                    val isSelected = selectedCategory == code
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) Color(0xFFD0BCFF) else Color(0xFF1A1C1E),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable { selectedCategory = code }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF1A1C1E) else Color(0xFFC2C7CF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedCategory == "MACROS") {
                    IconButton(
                        onClick = { showAddMacroDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Macro", tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                    }
                }

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = "Expand Keyboard",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Keys Row / Expanded Area
        val btnModifier = Modifier
            .height(34.dp)
            .padding(horizontal = 2.dp)

        val defaultBtnColors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1A1C1E),
            contentColor = Color(0xFFE2E2E6)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (selectedCategory) {
                "MODS" -> {
                    // Ctrl Modifier Toggle Button
                    Button(
                        onClick = onToggleCtrl,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCtrlPressed) Color(0xFF34D399) else Color(0xFF1A1C1E),
                            contentColor = if (isCtrlPressed) Color(0xFF1A1C1E) else Color(0xFFE2E2E6)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(4.dp),
                        modifier = btnModifier
                    ) {
                        Text(if (isCtrlPressed) "CTRL ●" else "Ctrl", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Alt Modifier Toggle Button
                    Button(
                        onClick = onToggleAlt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAltPressed) Color(0xFFD0BCFF) else Color(0xFF1A1C1E),
                            contentColor = if (isAltPressed) Color(0xFF1A1C1E) else Color(0xFFE2E2E6)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(4.dp),
                        modifier = btnModifier
                    ) {
                        Text(if (isAltPressed) "ALT ●" else "Alt", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Shift Modifier Toggle Button
                    Button(
                        onClick = onToggleShift,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isShiftPressed) Color(0xFFFFB300) else Color(0xFF1A1C1E),
                            contentColor = if (isShiftPressed) Color(0xFF1A1C1E) else Color(0xFFE2E2E6)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(4.dp),
                        modifier = btnModifier
                    ) {
                        Text(if (isShiftPressed) "SHIFT ●" else "Shift", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(onClick = { onSendRaw(byteArrayOf(3)) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) {
                        Text("Ctrl+C", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { onSendRaw(byteArrayOf(4)) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) {
                        Text("Ctrl+D", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { onSendRaw(byteArrayOf(26)) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) {
                        Text("Ctrl+Z", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { onSendRaw(byteArrayOf(9)) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) {
                        Text("Tab", fontSize = 11.sp)
                    }
                    Button(onClick = { onSendRaw(byteArrayOf(27)) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) {
                        Text("Esc", fontSize = 11.sp)
                    }
                    Button(onClick = onPasteClipboard, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF)), contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Color(0xFF1A1C1E), modifier = Modifier.size(14.dp))
                    }
                }

                "NAV" -> {
                    // Directional Arrows & Page navigation
                    Button(onClick = { onSendRaw("\u001B[D".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(4.dp), modifier = Modifier.size(34.dp)) { Text("◄", fontSize = 10.sp) }
                    Button(onClick = { onSendRaw("\u001B[A".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(4.dp), modifier = Modifier.size(34.dp)) { Text("▲", fontSize = 10.sp) }
                    Button(onClick = { onSendRaw("\u001B[B".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(4.dp), modifier = Modifier.size(34.dp)) { Text("▼", fontSize = 10.sp) }
                    Button(onClick = { onSendRaw("\u001B[C".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(4.dp), modifier = Modifier.size(34.dp)) { Text("►", fontSize = 10.sp) }
                    Button(onClick = { onSendRaw("\u001B[1~".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) { Text("Home", fontSize = 11.sp) }
                    Button(onClick = { onSendRaw("\u001B[4~".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) { Text("End", fontSize = 11.sp) }
                    Button(onClick = { onSendRaw("\u001B[5~".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) { Text("PgUp", fontSize = 11.sp) }
                    Button(onClick = { onSendRaw("\u001B[6~".toByteArray()) }, colors = defaultBtnColors, contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(4.dp), modifier = btnModifier) { Text("PgDn", fontSize = 11.sp) }
                }

                "SYM" -> {
                    listOf("|", "/", "\\", "-", "~", "_", "$", "{", "}", "[", "]", ";", "&", "<", ">", ":", "'", "\"", "@", "#", "%", "*", "=").forEach { char ->
                        Button(
                            onClick = { onInsertText(char) },
                            colors = defaultBtnColors,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = btnModifier
                        ) {
                            Text(char, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                "MACROS" -> {
                    customMacros.forEach { macro ->
                        Button(
                            onClick = {
                                onInsertText(macro)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3A4A), contentColor = Color(0xFFD0BCFF)),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = btnModifier
                        ) {
                            Text(macro, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        if (isExpanded) {
            HorizontalDivider(color = Color(0xFF44474E))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Expanded Quick Keys Grid", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("|", "/", "\\", "-", "~", "_", "$", "{", "}", "[", "]", ";", "&", "<", ">").forEach { symbol ->
                        OutlinedButton(
                            onClick = { onInsertText(symbol) },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(symbol, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (showAddMacroDialog) {
        AlertDialog(
            onDismissRequest = { showAddMacroDialog = false },
            title = { Text("Add Custom Command Shortcut Macro", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newMacroText,
                    onValueChange = { newMacroText = it },
                    label = { Text("Command (e.g., docker ps)", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color(0xFF44474E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newMacroText.isNotBlank()) {
                            customMacros.add(newMacroText.trim())
                            newMacroText = ""
                            showAddMacroDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF))
                ) {
                    Text("Add Shortcut", color = Color(0xFF1A1C1E))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMacroDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF2A2D35)
        )
    }
}
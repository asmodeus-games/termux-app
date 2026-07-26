package com.termux.ssh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.ssh.data.ServerEntity
import com.termux.ssh.TerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    serverId: Long,
    viewModel: TerminalViewModel,
    onBack: () -> Unit
) {
    val keys by viewModel.sshKeys.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portString by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf("PASSWORD") } // PASSWORD or KEY
    var password by remember { mutableStateOf("") }
    var sshKeyId by remember { mutableStateOf<Long?>(null) }
    var autoReconnect by remember { mutableStateOf(true) }

    var proxyType by remember { mutableStateOf("NONE") } // NONE, CLOUDFLARED, HTTP, SOCKS5, CUSTOM
    var proxyHost by remember { mutableStateOf("") }
    var proxyPortString by remember { mutableStateOf("1080") }
    var proxyCommand by remember { mutableStateOf("cloudflared access ssh --hostname %h") }

    var isEditMode by remember { mutableStateOf(false) }
    var currentServerEntity by remember { mutableStateOf<ServerEntity?>(null) }

    // Load server info if editing
    LaunchedEffect(serverId, servers) {
        if (serverId > 0L) {
            val s = servers.find { it.id == serverId }
            if (s != null) {
                isEditMode = true
                currentServerEntity = s
                name = s.name
                host = s.host
                portString = s.port.toString()
                username = s.username
                authType = s.authType
                password = s.password
                sshKeyId = s.sshKeyId
                autoReconnect = s.autoReconnect
                proxyType = s.proxyType
                proxyHost = s.proxyHost
                proxyPortString = s.proxyPort.toString()
                proxyCommand = s.proxyCommand.ifBlank { "cloudflared access ssh --hostname %h" }
            }
        }
    }

    var expandedKeyDropdown by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Server" else "Add Remote Server", color = Color(0xFFE2E2E6)) },
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
                    if (isEditMode) {
                        IconButton(
                            onClick = {
                                currentServerEntity?.let {
                                    viewModel.deleteServer(it) { onBack() }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1C1E))
            )
        },
        containerColor = Color(0xFF1A1C1E)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Alias / Name (e.g., Home Server)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFD0BCFF),
                    unfocusedBorderColor = Color(0xFF44474E)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_name_field")
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host Address (IP or Domain)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFD0BCFF),
                    unfocusedBorderColor = Color(0xFF44474E)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_host_field")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color(0xFF44474E)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("server_user_field")
                )

                OutlinedTextField(
                    value = portString,
                    onValueChange = { portString = it },
                    label = { Text("Port", color = Color.Gray) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color(0xFF44474E)
                    ),
                    modifier = Modifier.width(100.dp)
                )
            }

            // Auth Type selector
            Text("Authentication Method", color = Color(0xFFC2C7CF), fontSize = 14.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { authType = "PASSWORD" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (authType == "PASSWORD") Color(0xFFD0BCFF) else Color(0xFF2A2D35),
                        contentColor = if (authType == "PASSWORD") Color(0xFF1A1C1E) else Color(0xFFC2C7CF)
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Password",
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = { authType = "KEY" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (authType == "KEY") Color(0xFFD0BCFF) else Color(0xFF2A2D35),
                        contentColor = if (authType == "KEY") Color(0xFF1A1C1E) else Color(0xFFC2C7CF)
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "SSH Key",
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (authType == "PASSWORD") {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color(0xFF44474E)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_password_field")
                )
            } else {
                // Key Selector Dropdown
                val selectedKeyName = keys.find { it.id == sshKeyId }?.name ?: "Select an SSH Key..."
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expandedKeyDropdown = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("select_key_dropdown")
                    ) {
                        Text(selectedKeyName, color = Color.White)
                    }

                    DropdownMenu(
                        expanded = expandedKeyDropdown,
                        onDismissRequest = { expandedKeyDropdown = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A2D35))
                    ) {
                        if (keys.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No SSH Keys Found. Add some first!", color = Color.LightGray) },
                                onClick = { expandedKeyDropdown = false }
                            )
                        } else {
                            keys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.name, color = Color.White) },
                                    onClick = {
                                        sshKeyId = key.id
                                        expandedKeyDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Proxy & ProxyCommand Options
            Divider(color = Color(0xFF44474E), thickness = 1.dp)
            Text("Proxy / Tunneling (Optional)", color = Color(0xFFC2C7CF), fontSize = 14.sp, fontWeight = FontWeight.Bold)

            var expandedProxyDropdown by remember { mutableStateOf(false) }
            val proxyTypeLabel = when (proxyType) {
                "CLOUDFLARED" -> "Cloudflare / ProxyCommand"
                "HTTP" -> "HTTP Proxy"
                "SOCKS5" -> "SOCKS5 Proxy"
                "CUSTOM" -> "Custom Proxy Command"
                else -> "Direct Connection (No Proxy)"
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expandedProxyDropdown = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Proxy Type: $proxyTypeLabel", color = Color.White)
                }

                DropdownMenu(
                    expanded = expandedProxyDropdown,
                    onDismissRequest = { expandedProxyDropdown = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A2D35))
                ) {
                    DropdownMenuItem(
                        text = { Text("Direct Connection (No Proxy)", color = Color.White) },
                        onClick = { proxyType = "NONE"; expandedProxyDropdown = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Cloudflare / ProxyCommand (-o ProxyCommand=...)", color = Color.White) },
                        onClick = {
                            proxyType = "CLOUDFLARED"
                            if (proxyCommand.isBlank()) proxyCommand = "cloudflared access ssh --hostname %h"
                            expandedProxyDropdown = false
                        }
                    )
DropdownMenuItem(
                        text = { Text("HTTP Proxy", color = Color.White) },
                        onClick = { proxyType = "HTTP"; expandedProxyDropdown = false }
                    )
                    DropdownMenuItem(
                        text = { Text("SOCKS5 Proxy", color = Color.White) },
                        onClick = { proxyType = "SOCKS5"; expandedProxyDropdown = false }
                    )
                }
            }

            if (proxyType == "CLOUDFLARED" || proxyType == "CUSTOM") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = proxyCommand,
                        onValueChange = { proxyCommand = it },
                        label = { Text("ProxyCommand String", color = Color.Gray) },
                        placeholder = { Text("e.g., cloudflared access ssh --hostname %h", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFD0BCFF),
                            unfocusedBorderColor = Color(0xFF44474E)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "💡 Auto-detects cloudflared in Termux (/data/data/com.termux/files/usr/bin/cloudflared) or specify custom binary path.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            } else if (proxyType == "HTTP" || proxyType == "SOCKS5") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = { Text("Proxy Host", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFD0BCFF),
                            unfocusedBorderColor = Color(0xFF44474E)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = proxyPortString,
                        onValueChange = { proxyPortString = it },
                        label = { Text("Port", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFD0BCFF),
                            unfocusedBorderColor = Color(0xFF44474E)
                        ),
                        modifier = Modifier.width(100.dp)
                    )
                }
            }

            // Connection rules
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Auto Reconnect", color = Color.White, fontSize = 16.sp)
                    Text("Re-establish connection if dropped", color = Color.Gray, fontSize = 12.sp)
                }
                Switch(
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF1A1C1E),
                        checkedTrackColor = Color(0xFFD0BCFF)
                    )
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (name.isNotBlank() && host.isNotBlank() && username.isNotBlank()) {
                        val finalPort = portString.toIntOrNull() ?: 22
                        val finalProxyPort = proxyPortString.toIntOrNull() ?: 1080
                        val entity = ServerEntity(
                            id = if (isEditMode) serverId else 0L,
                            name = name,
                            host = host,
                            port = finalPort,
                            username = username,
                            authType = authType,
                            password = password,
                            sshKeyId = sshKeyId,
                            autoReconnect = autoReconnect,
                            lastUsed = System.currentTimeMillis(),
                            proxyType = proxyType,
                            proxyHost = proxyHost,
                            proxyPort = finalProxyPort,
                            proxyCommand = proxyCommand
                        )
                        viewModel.saveServer(entity) {
                            onBack()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD0BCFF),
                    disabledContainerColor = Color(0xFF44474E)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_server_btn"),
                enabled = name.isNotBlank() && host.isNotBlank() && username.isNotBlank()
            ) {
                Icon(Icons.Default.Save, contentDescription = "Save", tint = Color(0xFF1A1C1E))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Server Configuration", color = Color(0xFF1A1C1E))
            }
        }
    }
}
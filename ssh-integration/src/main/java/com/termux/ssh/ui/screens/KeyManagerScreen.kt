package com.termux.ssh.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.ssh.data.SSHKeyEntity
import com.termux.ssh.TerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagerScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val keys by viewModel.sshKeys.collectAsStateWithLifecycle()
    
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedKeyForDetail by remember { mutableStateOf<SSHKeyEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Key Manager", color = Color(0xFFE2E2E6)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFE2E2E6)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1C1E))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showGenerateDialog = true },
                containerColor = Color(0xFFD0BCFF),
                contentColor = Color(0xFF1A1C1E),
                modifier = Modifier.testTag("generate_key_fab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Generate")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate Key", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        containerColor = Color(0xFF1A1C1E)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                "Natively generated RSA 2048 keys can be used for secure, passwordless authentication. Paste the public key into your headless server's ~/.ssh/authorized_keys file.",
                color = Color(0xFFC2C7CF),
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.autoDetectSshKeys { addedCount ->
                            if (addedCount > 0) {
                                Toast.makeText(context, "Auto-detected & imported $addedCount key(s) from ~/.ssh/", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No new keys found in ~/.ssh/", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Auto detect", tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Auto-Detect ~/.ssh", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { showImportDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2D35)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color(0xFF44474E), RoundedCornerShape(8.dp))
                        .testTag("import_key_btn")
                ) {
                    Icon(Icons.Default.Key, contentDescription = "Import Key", tint = Color(0xFFD0BCFF), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import Private Key", color = Color(0xFFE2E2E6), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (keys.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = "No Keys",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No SSH keys stored. Generate or import one!", color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(keys) { key ->
                        KeyListItem(
                            key = key,
                            onSelect = { selectedKeyForDetail = key },
                            onDelete = { viewModel.deleteSshKey(key) {} }
                        )
                    }
                }
            }
        }

        // Generate Dialog
        if (showGenerateDialog) {
            var keyName by remember { mutableStateOf("") }
            var passphrase by remember { mutableStateOf("") }
            var generating by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { if (!generating) showGenerateDialog = false },
                containerColor = Color(0xFF2A2D35),
                title = { Text("Generate RSA Keypair", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = keyName,
                            onValueChange = { keyName = it },
                            label = { Text("Key Name (e.g., prod_server_key)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color(0xFF44474E)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("key_name_input")
                        )

                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase (Optional)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color(0xFF44474E)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (generating) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFFD0BCFF))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (keyName.isNotBlank()) {
                                generating = true
                                viewModel.generateRsaKeypair(keyName, passphrase) {
                                    generating = false
                                    showGenerateDialog = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF)),
                        enabled = keyName.isNotBlank() && !generating
                    ) {
                        Text("Generate", color = Color(0xFF1A1C1E))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showGenerateDialog = false },
                        enabled = !generating
                    ) {
                        Text("Cancel", color = Color.LightGray)
                    }
                }
            )
        }

        // Import Dialog
        if (showImportDialog) {
            var keyName by remember { mutableStateOf("") }
            var privateKeyContent by remember { mutableStateOf("") }
            var passphrase by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                containerColor = Color(0xFF2A2D35),
                title = { Text("Import Private Key", color = Color.White) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = keyName,
                            onValueChange = { keyName = it },
                            label = { Text("Key Name", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color(0xFF44474E)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = privateKeyContent,
                            onValueChange = { privateKeyContent = it },
                            label = { Text("Paste Private Key content", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color(0xFF44474E)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .testTag("import_private_key_input")
                        )

                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase (Optional)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFD0BCFF),
                                unfocusedBorderColor = Color(0xFF44474E)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (keyName.isNotBlank() && privateKeyContent.isNotBlank()) {
                                viewModel.saveSshKey(keyName, privateKeyContent, "", passphrase) {
                                    showImportDialog = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF)),
                        enabled = keyName.isNotBlank() && privateKeyContent.isNotBlank()
                    ) {
                        Text("Save", color = Color(0xFF1A1C1E))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) {
                        Text("Cancel", color = Color.LightGray)
                    }
                }
            )
        }

        // Details Sheet / Dialog
        selectedKeyForDetail?.let { key ->
            var showPrivateKey by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { selectedKeyForDetail = null },
                containerColor = Color(0xFF2A2D35),
                title = { Text(key.name, color = Color.White) },
                text = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text("Public Key:", color = Color(0xFFD0BCFF), fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1A1C1E), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFF44474E), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = key.publicKey.ifBlank { "No public key content available (imported private-only)" },
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.heightIn(max = 100.dp)
                                    )
                                    if (key.publicKey.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        IconButton(
                                            onClick = {
                                                viewModel.copyToClipboard(context, "SSH Public Key", key.publicKey)
                                            },
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Copy Public Key",
                                                tint = Color(0xFFD0BCFF)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Private Key:", color = Color(0xFFD0BCFF), fontSize = 14.sp)
                                IconButton(onClick = { showPrivateKey = !showPrivateKey }) {
                                    Icon(
                                        imageVector = if (showPrivateKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Private Key",
                                        tint = Color.Gray
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1A1C1E), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFF44474E), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = if (showPrivateKey) key.privateKey else "• • • • • • • • • • • • • • • • • • • • • • • •\n• • • • • • • • • • • • • • • • • • • • • • • •",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.heightIn(max = 100.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    IconButton(
                                        onClick = {
                                            viewModel.copyToClipboard(context, "SSH Private Key", key.privateKey)
                                        },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy Private Key",
                                            tint = Color(0xFFD0BCFF)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { selectedKeyForDetail = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF))
                    ) {
                        Text("Close", color = Color(0xFF1A1C1E))
                    }
                }
            )
        }
    }
}

@Composable
fun KeyListItem(
    key: SSHKeyEntity,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(1.dp, Color(0xFF44474E), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2D35)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1A1C1E), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Key, contentDescription = "Key Icon", tint = Color(0xFFD0BCFF))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(key.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (key.publicKey.isNotBlank()) "RSA 2048 Bit" else "Imported (Private-only)",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Key", tint = Color(0xFFFF5252))
            }
        }
    }
}
package com.termux.ssh.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.ssh.data.AppDatabase
import com.termux.ssh.data.ServerEntity
import com.termux.ssh.SFTPItem
import com.termux.ssh.SFTPManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpFileManagerDialog(
    server: ServerEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }

    var currentPath by remember { mutableStateOf("") }
    var fileItems by remember { mutableStateOf<List<SFTPItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Upload / Download state
    var isTransferring by remember { mutableStateOf(false) }
    var transferStatusText by remember { mutableStateOf("") }
    var transferProgress by remember { mutableFloatStateOf(0f) }

    // Selected file for download
    var pendingDownloadItem by remember { mutableStateOf<SFTPItem?>(null) }

    // New folder dialog
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // Delete confirmation dialog
    var pendingDeleteItem by remember { mutableStateOf<SFTPItem?>(null) }

    // Function to reload directory
    fun loadDirectory(targetPath: String = currentPath) {
        scope.launch {
            isLoading = true
            errorMessage = null
            
            val path = if (targetPath.isBlank()) {
                val pwd = SFTPManager.getWorkingDirectory(server, database)
                currentPath = pwd
                pwd
            } else {
                currentPath = targetPath
                targetPath
            }

            val result = SFTPManager.listFiles(server, database, path)
            result.onSuccess { items ->
                fileItems = items
                isLoading = false
            }.onFailure { err ->
                errorMessage = err.localizedMessage ?: "Failed to load directory"
                isLoading = false
            }
        }
    }

    // Load initial directory on launch
    LaunchedEffect(server.id) {
        loadDirectory("")
    }

    // Launcher to pick a local file to send (upload)
    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { pickedUri ->
            scope.launch {
                val fileName = getFileNameFromUri(context, pickedUri) ?: "uploaded_file"
                isTransferring = true
                transferStatusText = "Uploading $fileName..."
                transferProgress = 0f

                val result = SFTPManager.uploadFile(
                    context = context,
                    server = server,
                    database = database,
                    localUri = pickedUri,
                    remoteDir = currentPath,
                    targetFileName = fileName,
                    onProgress = { progress ->
                        transferProgress = progress
                    }
                )

                isTransferring = false
                result.onSuccess {
                    Toast.makeText(context, "File uploaded successfully!", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                }.onFailure { err ->
                    Toast.makeText(context, "Upload failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Launcher to save downloaded remote file locally
    val downloadSaverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri: Uri? ->
        val itemToDownload = pendingDownloadItem
        pendingDownloadItem = null

        if (targetUri != null && itemToDownload != null) {
            scope.launch {
                isTransferring = true
                transferStatusText = "Downloading ${itemToDownload.name}..."
                transferProgress = 0f

                val result = SFTPManager.downloadFile(
                    context = context,
                    server = server,
                    database = database,
                    remoteFilePath = itemToDownload.path,
                    targetUri = targetUri,
                    onProgress = { progress ->
                        transferProgress = progress
                    }
                )

                isTransferring = false
                result.onSuccess {
                    Toast.makeText(context, "Download complete!", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, "Download failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1C1E)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A2D35))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SFTP File Explorer",
                            color = Color(0xFFE2E2E6),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${server.name} (${server.username}@${server.host})",
                            color = Color(0xFFD0BCFF),
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Action Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF22242B))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Up directory button
                    IconButton(
                        onClick = {
                            if (currentPath.isNotBlank() && currentPath != "/") {
                                val parent = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
                                loadDirectory(parent)
                            }
                        },
                        enabled = currentPath.isNotBlank() && currentPath != "/" && !isLoading
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Up Directory",
                            tint = if (currentPath.isNotBlank() && currentPath != "/") Color(0xFFD0BCFF) else Color.Gray
                        )
                    }

                    // Home button
                    IconButton(
                        onClick = { loadDirectory("") },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Home Directory", tint = Color(0xFFD0BCFF))
                    }

                    // Refresh button
                    IconButton(
                        onClick = { loadDirectory(currentPath) },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFFD0BCFF))
                    }

                    // Create folder button
                    IconButton(
                        onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = Color(0xFFD0BCFF))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Upload (Send) File Button
                    Button(
                        onClick = { uploadPickerLauncher.launch("*/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        enabled = !isLoading && !isTransferring
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Upload", tint = Color(0xFF1A1C1E), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Send File", color = Color(0xFF1A1C1E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Current Path Breadcrumb display
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1C1E))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Path: ",
                        color = Color(0xFFC2C7CF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = currentPath.ifEmpty { "/" },
                        color = Color(0xFF34D399),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Transfer Progress Overlay Banner
                if (isTransferring) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A2D35))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(transferStatusText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("${(transferProgress * 100).toInt()}%", color = Color(0xFFD0BCFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { transferProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Color(0xFFD0BCFF),
                            trackColor = Color(0xFF44474E)
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF44474E)))

                // Content Area: Loading / Error / File list
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFFD0BCFF))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Fetching remote directory...", color = Color(0xFFC2C7CF), fontSize = 13.sp)
                        }
                    } else if (errorMessage != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text("Error loading files", color = Color(0xFFFF5252), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(errorMessage ?: "", color = Color.White, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { loadDirectory(currentPath) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF))
                            ) {
                                Text("Retry", color = Color(0xFF1A1C1E))
                            }
                        }
                    } else if (fileItems.isEmpty()) {
                        Text("This folder is empty.", color = Color(0xFFC2C7CF), fontSize = 14.sp)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(fileItems) { item ->
                                SftpItemRow(
                                    item = item,
                                    onClick = {
                                        if (item.isDirectory) {
                                            loadDirectory(item.path)
                                        }
                                    },
                                    onDownload = {
                                        pendingDownloadItem = item
                                        downloadSaverLauncher.launch(item.name)
                                    },
                                    onDelete = {
                                        pendingDeleteItem = item
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Dialog to create a new remote directory
        if (showNewFolderDialog) {
            AlertDialog(
                onDismissRequest = { showNewFolderDialog = false },
                containerColor = Color(0xFF2A2D35),
                title = { Text("Create New Remote Directory", color = Color.White, fontSize = 16.sp) },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Directory Name", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFD0BCFF),
                            unfocusedBorderColor = Color(0xFF44474E)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val name = newFolderName.trim()
                            if (name.isNotEmpty()) {
                                showNewFolderDialog = false
                                scope.launch {
                                    val targetDir = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                                    val result = SFTPManager.createDirectory(server, database, targetDir)
                                    result.onSuccess {
                                        Toast.makeText(context, "Folder created!", Toast.LENGTH_SHORT).show()
                                        loadDirectory(currentPath)
                                    }.onFailure { err ->
                                        Toast.makeText(context, "Failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF)),
                        enabled = newFolderName.isNotBlank()
                    ) {
                        Text("Create", color = Color(0xFF1A1C1E))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFolderDialog = false }) {
                        Text("Cancel", color = Color(0xFFD0BCFF))
                    }
                }
            )
        }

        // Dialog to confirm file/folder deletion
        pendingDeleteItem?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingDeleteItem = null },
                containerColor = Color(0xFF2A2D35),
                title = { Text("Delete Remote ${if (item.isDirectory) "Directory" else "File"}?", color = Color.White, fontSize = 16.sp) },
                text = {
                    Text("Are you sure you want to delete '${item.name}'?\nThis action cannot be undone.", color = Color(0xFFC2C7CF), fontSize = 13.sp)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetItem = item
                            pendingDeleteItem = null
                            scope.launch {
                                val result = SFTPManager.deleteItem(server, database, targetItem.path, targetItem.isDirectory)
                                result.onSuccess {
                                    Toast.makeText(context, "Deleted successfully", Toast.LENGTH_SHORT).show()
                                    loadDirectory(currentPath)
                                }.onFailure { err ->
                                    Toast.makeText(context, "Delete failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) {
                        Text("Delete", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteItem = null }) {
                        Text("Cancel", color = Color(0xFFD0BCFF))
                    }
                }
            )
        }
    }
}

@Composable
fun SftpItemRow(
    item: SFTPItem,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = if (item.isDirectory) "Directory" else "File",
            tint = if (item.isDirectory) Color(0xFFFFB300) else Color(0xFFD0BCFF),
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!item.isDirectory) {
                    Text(
                        text = formatFileSize(item.size),
                        color = Color(0xFF34D399),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = dateFormat.format(Date(item.lastModified)),
                    color = Color(0xFFC2C7CF),
                    fontSize = 11.sp
                )
            }
        }

        // Action icons
        if (!item.isDirectory) {
            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Download, contentDescription = "Download File", tint = Color(0xFF34D399), modifier = Modifier.size(20.dp))
            }
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252).copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2D35)))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var fileName: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = it.getString(nameIndex)
            }
        }
    }
    return fileName ?: uri.lastPathSegment
}
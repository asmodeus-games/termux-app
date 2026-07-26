package com.termux.ssh

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.OutputStream

data class SSHConsoleLine(
    val text: String,
    val type: LineType
) {
    enum class LineType {
        COMMAND,
        PROMPT,
        OUTPUT,
        ERROR,
        SYSTEM
    }
}

class TerminalSession(
    val serverId: Long,
    val serverName: String,
    val onStateChanged: () -> Unit = {}
) {
    val lines = mutableStateListOf<SSHConsoleLine>()
    var currentLineText by mutableStateOf("")
    var isConnected by mutableStateOf(false)
    var isReconnecting by mutableStateOf(false)
    var reconnectAttempts by mutableStateOf(0)
    var fontScale by mutableStateOf(14f) // Text font size in SP

    // Connection Traffic & Speed Metrics
    var rxBytes by mutableLongStateOf(0L)
    var txBytes by mutableLongStateOf(0L)
    var rxSpeedBytesPerSec by mutableLongStateOf(0L)
    var txSpeedBytesPerSec by mutableLongStateOf(0L)

    var outputStream: OutputStream? = null
    var session: com.jcraft.jsch.Session? = null
    var channel: com.jcraft.jsch.ChannelShell? = null
    var localProcess: Process? = null
    var isLocal: Boolean = false

    fun recordRx(count: Int) {
        if (count > 0) {
            rxBytes += count
        }
    }

    fun recordTx(count: Int) {
        if (count > 0) {
            txBytes += count
        }
    }

    init {
        addSystemLine("Session initialized for $serverName")
    }

    fun addSystemLine(text: String) {
        lines.add(SSHConsoleLine(text, SSHConsoleLine.LineType.SYSTEM))
        trimLinesToLimit()
        onStateChanged()
    }

    fun addCommandLine(text: String) {
        lines.add(SSHConsoleLine(text, SSHConsoleLine.LineType.COMMAND))
        trimLinesToLimit()
        onStateChanged()
    }

    fun appendOutput(chunk: String) {
        val cleaned = cleanAnsiCodes(chunk)
        
        // Handle carriage returns by overwriting or splitting
        val parts = cleaned.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        
        if (parts.size == 1) {
            currentLineText += parts[0]
        } else {
            // First part completes currentLineText
            val first = currentLineText + parts[0]
            if (first.isNotEmpty()) {
                lines.add(parseLineType(first))
            }
            
            // Middle parts are independent complete lines
            for (i in 1 until parts.size - 1) {
                if (parts[i].isNotEmpty()) {
                    lines.add(parseLineType(parts[i]))
                }
            }
            
            // Last part is the new active line
            currentLineText = parts[parts.size - 1]
        }
        
        trimLinesToLimit()
        onStateChanged()
    }

    private fun trimLinesToLimit() {
        // Keep last 1000 lines to prevent memory issues and optimize performance
        if (lines.size > 1000) {
            lines.removeRange(0, lines.size - 1000)
        }
    }

    private fun parseLineType(line: String): SSHConsoleLine {
        val trimmed = line.trim()
        return when {
            trimmed.contains("Permission denied") || 
            trimmed.contains("error:") || 
            trimmed.contains("failed") || 
            trimmed.startsWith("bash: ") -> {
                SSHConsoleLine(line, SSHConsoleLine.LineType.ERROR)
            }
            trimmed.endsWith("$") || 
            trimmed.endsWith("#") || 
            trimmed.contains("~$") || 
            trimmed.contains(":/#") || 
            trimmed.contains("@") && trimmed.contains(":") -> {
                SSHConsoleLine(line, SSHConsoleLine.LineType.PROMPT)
            }
            else -> {
                SSHConsoleLine(line, SSHConsoleLine.LineType.OUTPUT)
            }
        }
    }

    private fun cleanAnsiCodes(text: String): String {
        // Broad regex to clean ANSI terminal escape codes
        val ansiRegex = "\\u001B\\[[;\\d]*[a-zA-Z]".toRegex()
        return text.replace(ansiRegex, "")
    }

    fun clearConsole() {
        lines.clear()
        currentLineText = ""
        addSystemLine("Console cleared")
    }
}
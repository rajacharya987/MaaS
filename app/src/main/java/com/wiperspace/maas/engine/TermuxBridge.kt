package com.wiperspace.maas.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.wiperspace.maas.receiver.TermuxResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class TermuxCommandResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val errmsg: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class EC2EnvironmentStatus(
    val isTermuxInstalled: Boolean = false,
    val hasNode: Boolean = false,
    val hasPython: Boolean = false,
    val hasCloudflared: Boolean = false,
    val hasGit: Boolean = false,
    val isBootstrapped: Boolean = false,
    val termuxPrefixPath: String = "/data/data/com.termux/files/usr"
)

object TermuxBridge {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private val _commandResults = MutableSharedFlow<TermuxCommandResult>(extraBufferCapacity = 64)
    val commandResults: SharedFlow<TermuxCommandResult> = _commandResults.asSharedFlow()

    private val _envStatus = MutableStateFlow(EC2EnvironmentStatus())
    val envStatus: StateFlow<EC2EnvironmentStatus> = _envStatus.asStateFlow()

    fun onCommandResultReceived(stdout: String, stderr: String, exitCode: Int, errmsg: String) {
        val result = TermuxCommandResult(stdout, stderr, exitCode, errmsg)
        _commandResults.tryEmit(result)
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun executeCommand(
        context: Context,
        commandPath: String,
        args: Array<String> = emptyArray(),
        workDir: String = "/data/data/com.termux/files/home",
        inBackground: Boolean = true
    ): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                action = RUN_COMMAND_ACTION
                putExtra(EXTRA_COMMAND_PATH, commandPath)
                putExtra(EXTRA_ARGUMENTS, args)
                putExtra(EXTRA_WORKDIR, workDir)
                putExtra(EXTRA_BACKGROUND, inBackground)
                putExtra(EXTRA_SESSION_ACTION, "0")

                val resultIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                    action = TermuxResultReceiver.ACTION_TERMUX_RESULT
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, 1001, resultIntent, flags)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun checkLocalEnvironment(context: Context): EC2EnvironmentStatus = withContext(Dispatchers.IO) {
        val termuxInstalled = isTermuxInstalled(context)
        val prefix = "/data/data/com.termux/files/usr"
        val hasNode = java.io.File("$prefix/bin/node").exists()
        val hasPython = java.io.File("$prefix/bin/python").exists() || java.io.File("$prefix/bin/python3").exists()
        val hasCf = java.io.File("$prefix/bin/cloudflared").exists()
        val hasGit = java.io.File("$prefix/bin/git").exists()
        val isBootstrapped = java.io.File("$prefix/bin/sh").exists()

        val status = EC2EnvironmentStatus(
            isTermuxInstalled = termuxInstalled,
            hasNode = hasNode,
            hasPython = hasPython,
            hasCloudflared = hasCf,
            hasGit = hasGit,
            isBootstrapped = isBootstrapped,
            termuxPrefixPath = prefix
        )
        _envStatus.value = status
        status
    }
}

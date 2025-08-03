package net.isaeff.android.asproxy

import android.content.Context
import android.content.pm.PackageInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState // Import collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import android.content.SharedPreferences
import androidx.compose.runtime.DisposableEffect


import net.isaeff.android.asproxy.ui.theme.AAPTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AAPTheme {
                MainScreen()
            }
        }
        runMigrations()
        val packageInfo: PackageInfo = applicationContext
            .packageManager
            .getPackageInfo(applicationContext.packageName, 0)
        val versionName = packageInfo.versionName

        val osVer = Build.VERSION.RELEASE
        val arch = System.getProperty("os.arch")
        AAPLog.append("AAP version $versionName running on Android $osVer ($arch)")

    }

    private fun runMigrations() {
        val prefs = getSharedPreferences("aap_prefs", Context.MODE_PRIVATE)
        with(prefs.edit()) {
            if (!prefs.contains("auto_retry_on_failure")) {
                putBoolean("auto_retry_on_failure", true)
                AAPLog.append("Migration: Set auto_retry_on_failure to default (true)")
            }
            if (!prefs.contains("retry_timeout_seconds")) {
                putString("retry_timeout_seconds", "30")
                AAPLog.append("Migration: Set retry_timeout_seconds to default (30)")
            }
            apply()
        }
    }

    companion object {
        fun aaplog(message: String) {
            AAPLog.append(message)
        }
    }
}

// Removed enum class ConnectionState {} as it's now in ConnectionStateHolder.kt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current

    // Helper to format byte counts nicely (KB / MB / GB)
    fun formatBytes(value: Long): String =
        android.text.format.Formatter.formatShortFileSize(context, value)

    // Get SharedPreferences instance once and remember it
    val prefs = remember { context.getSharedPreferences("aap_prefs", Context.MODE_PRIVATE) }

    // Load saved preferences on first composition
    var sshServer by rememberSaveable { mutableStateOf("") }
    var remotePort by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // State for server key preference and checkbox
    var serverKeyPreferenceValue by rememberSaveable { mutableStateOf("") }
    var storeServerKeyChecked by rememberSaveable { mutableStateOf(false) }
    // Auto-connect preference; enabled by default on first launch
    var autoConnectChecked by rememberSaveable { mutableStateOf(true) }
    var autoRetryChecked by rememberSaveable { mutableStateOf(true) }
    var retryTimeout by rememberSaveable { mutableStateOf("30") }
    var showClearKeyDialog by rememberSaveable { mutableStateOf(false) }

    // Observe the connection state from the shared holder
    val connectionState by ConnectionStateHolder.connectionState.collectAsState()
    // Observe traffic bytes (rx, tx)
    val traffic by ConnectionStateHolder.trafficBytes.collectAsState()
 
    // Removed Broadcast receiver logic as state is now observed from ConnectionStateHolder

    // Initial load of saved values from SharedPreferences
    LaunchedEffect(Unit) {
        sshServer = prefs.getString("ssh_server", "") ?: ""
        remotePort = prefs.getString("remote_port", "") ?: ""
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        // Initialize server key preference and checkbox state
        serverKeyPreferenceValue = prefs.getString("server_key", "") ?: ""
        // Checkbox is checked if no key is stored (meaning we want to store a new one)
        storeServerKeyChecked = serverKeyPreferenceValue.isBlank()

        // Load auto-connect preference (default true on first start)
        autoConnectChecked = prefs.getBoolean("auto_connect", true)
        autoRetryChecked = prefs.getBoolean("auto_retry_on_failure", true)
        retryTimeout = prefs.getString("retry_timeout_seconds", "30") ?: "30"
    }

    // Listen for changes to SharedPreferences, specifically the "server_key"
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "server_key") {
                val newKey = sharedPreferences.getString("server_key", "") ?: ""
                serverKeyPreferenceValue = newKey
                // Update checkbox state: checked if no key, unchecked if key exists
                storeServerKeyChecked = newKey.isBlank()
                AAPLog.append("MainActivity: server_key preference changed to '$newKey', checkbox state updated to $storeServerKeyChecked")
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Removed launch-time auto-connect; auto-connect is now handled only by BootCompletedReceiver

    val scrollState = rememberScrollState()
    val isFormValid = sshServer.isNotBlank() && remotePort.isNotBlank() &&
            username.isNotBlank() && password.isNotBlank() && remotePort.all { it.isDigit() } &&
            (!autoRetryChecked || (retryTimeout.toIntOrNull() ?: 0) > 0)

    // Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AAPLog.append("Notification permission granted")
        } else {
            AAPLog.append("Notification permission denied")
        }
    }

    // Check if we need to request notification permission
    val shouldRequestNotificationPermission = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(shouldRequestNotificationPermission) {
        if (shouldRequestNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Android As Proxy")
                            Text(
                                text = when (connectionState) {
                                    ConnectionState.CONNECTED -> "● Connected"
                                    ConnectionState.DISCONNECTED -> "● Disconnected"
                                    ConnectionState.CONNECTING -> "● Connecting..."
                                    ConnectionState.DISCONNECTING -> "● Disconnecting..."
                                },
                                color = when (connectionState) {
                                    ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                    ConnectionState.DISCONNECTED -> Color(0xFFF44336)
                                    ConnectionState.CONNECTING -> Color(0xFFFFA000)
                                    ConnectionState.DISCONNECTING -> Color(0xFFFFA000)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                        Text(
                            text = "Traffic ${formatBytes(traffic.first)} in / ${formatBytes(traffic.second)} out",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // SSH Server Input
                OutlinedTextField(
                    value = sshServer,
                    onValueChange = { sshServer = it },
                    label = { Text("SSH Server") },
                    placeholder = { Text("example.com:22 or 192.168.1.10:2222") },
                    supportingText = { Text("Enter SSH server address with optional port") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    // Disable input when connecting or connected
                    enabled = connectionState == ConnectionState.DISCONNECTED
                )

                // Remote Port Input
                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { remotePort = it },
                    label = { Text("Remote SSH Port") },
                    placeholder = { Text("10800") },
                    supportingText = { Text("Port to forward local SOCKS proxy to on SSH server") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    visualTransformation = if (remotePort.isNotEmpty() && !remotePort.all { it.isDigit() }) {
                        VisualTransformation.None // Show invalid input
                    } else {
                        VisualTransformation.None
                    },
                    // Disable input when connecting or connected
                    enabled = connectionState == ConnectionState.DISCONNECTED
                )

                // Username Input
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("SSH Username") },
                    placeholder = { Text("username") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    ),
                    // Disable input when connecting or connected
                    enabled = connectionState == ConnectionState.DISCONNECTED
                )

                // Password Input
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("SSH Password") },
                    placeholder = { Text("password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    // Disable input when connecting or connected
                    enabled = connectionState == ConnectionState.DISCONNECTED
                )

                // Checkbox for server key
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = storeServerKeyChecked,
                        onCheckedChange = { newValue ->
                            if (newValue) { // User wants to check the box
                                if (serverKeyPreferenceValue.isNotBlank()) {
                                    // If a key exists, prompt to clear it
                                    showClearKeyDialog = true
                                } else {
                                    storeServerKeyChecked = true
                                }
                            } else { // User wants to uncheck the box
                                storeServerKeyChecked = false
                            }
                        },
                        enabled = serverKeyPreferenceValue.isNotBlank() && connectionState == ConnectionState.DISCONNECTED
                    )
                    Text("Store new server key")
                }

                // Checkbox for auto-connect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = autoConnectChecked,
                        onCheckedChange = { newValue ->
                            autoConnectChecked = newValue
                            with(prefs.edit()) {
                                putBoolean("auto_connect", newValue)
                                apply()
                            }
                        }
                    )
                    Text("Enable auto-connect on system boot")
                }

                // Checkbox for auto-retry on failure
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = autoRetryChecked,
                        onCheckedChange = { newValue ->
                            autoRetryChecked = newValue
                            with(prefs.edit()) {
                                putBoolean("auto_retry_on_failure", newValue)
                                apply()
                            }
                        },
                        enabled = connectionState == ConnectionState.DISCONNECTED
                    )
                    Text("Auto-retry on failure")
                }

                // Timeout Input for auto-retry
                OutlinedTextField(
                    value = retryTimeout,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            retryTimeout = newValue
                            with(prefs.edit()) {
                                putString("retry_timeout_seconds", newValue)
                                apply()
                            }
                        }
                    },
                    label = { Text("Timeout (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    enabled = autoRetryChecked && connectionState == ConnectionState.DISCONNECTED,
                    supportingText = { if (autoRetryChecked && (retryTimeout.toIntOrNull() ?: 0) <= 0) Text("Must be a positive number") },
                    isError = autoRetryChecked && (retryTimeout.toIntOrNull() ?: 0) <= 0
                )

                // Dialog for clearing host key
                if (showClearKeyDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showClearKeyDialog = false
                            // If dialog is dismissed without confirming, revert checkbox state
                            // (it was attempted to be checked, but not confirmed)
                            storeServerKeyChecked = false
                        },
                        title = { Text("Confirm Host Key Removal") },
                        text = { Text("Are you sure you want to remove the current host key:\n\"$serverKeyPreferenceValue\"?\n\nThis will allow a new key to be stored on the next successful connection.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val oldKey = serverKeyPreferenceValue // Capture before clearing
                                    with(prefs.edit()) {
                                        remove("server_key")
                                        apply()
                                    }
                                    serverKeyPreferenceValue = "" // Update state
                                    storeServerKeyChecked = true // Checkbox becomes checked and disabled
                                    AAPLog.append("Host key \"$oldKey\" cleared by user")
                                    showClearKeyDialog = false
                                }
                            ) {
                                Text("Yes, Clear Key")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showClearKeyDialog = false
                                    // Revert checkbox state if dialog dismissed without confirming
                                    storeServerKeyChecked = false
                                }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            Row(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = {
                        // Check notification permission again right before connecting
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@Button
                        }

                        when (connectionState) {
                            ConnectionState.DISCONNECTED -> {
                                MainActivity.aaplog("Attempting to start SOCKS server via SSH to $sshServer")
                                // Save preferences on connect attempt
                                with(prefs.edit()) {
                                    putString("ssh_server", sshServer)
                                    putString("remote_port", remotePort)
                                    putString("username", username)
                                    putString("password", password)
                                    apply()
                                }

                                // Start the foreground service with parameters
                                try {
                                    val intent = Intent(context, SocksForegroundService::class.java).apply {
                                        putExtra("ssh_server", sshServer)
                                        putExtra("remote_port", remotePort.toInt()) // Ensure it's an Int
                                        putExtra("username", username)
                                        putExtra("password", password)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        AAPLog.append("Starting foreground service")
                                        context.startForegroundService(intent)
                                    } else {
                                        AAPLog.append("Starting regular service")
                                        context.startService(intent)
                                    }
                                } catch (e: Exception) {
                                    AAPLog.append("Failed to start service: ${e.message}")
                                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED) // Revert state on immediate failure
                                }
                                // State will be updated by the service (CONNECTING -> CONNECTED or DISCONNECTED)
                            }
                            ConnectionState.CONNECTED -> {
                                MainActivity.aaplog("Attempting to stop SOCKS server")
                                // Stop the foreground service with explicit stop action
                                val intent = Intent(context, SocksForegroundService::class.java).apply {
                                    action = "net.isaeff.android.asproxy.action.STOP_SERVICE"
                                }
                                context.startService(intent)
                                // State will be updated by the service (DISCONNECTING -> DISCONNECTED)
                            }
                            else -> {
                                // Do nothing if already connecting or disconnecting
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    // Button is enabled only when disconnected or connected
                    enabled = isFormValid && (connectionState == ConnectionState.DISCONNECTED ||
                            connectionState == ConnectionState.CONNECTED),
                    colors = when (connectionState) {
                        ConnectionState.DISCONNECTED -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )

                        ConnectionState.CONNECTED -> ButtonDefaults.buttonColors(
                            containerColor = Color.Red
                        )

                        else -> ButtonDefaults.buttonColors(
                            containerColor = Color.Gray // Grey out when connecting/disconnecting
                        )
                    }
                ) {
                    Text(
                        text = when (connectionState) {
                            ConnectionState.DISCONNECTED -> "Connect"
                            ConnectionState.CONNECTED -> "Disconnect"
                            ConnectionState.CONNECTING -> "Connecting..."
                            ConnectionState.DISCONNECTING -> "Disconnecting..."
                        }
                    )
                }
                Button(
                    onClick = {
                        context.startActivity(Intent(context, LogViewActivity::class.java))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Show log")
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    AAPTheme {
        MainScreen()
    }
}

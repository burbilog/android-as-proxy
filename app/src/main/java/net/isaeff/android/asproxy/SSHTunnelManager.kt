package net.isaeff.android.asproxy

import android.content.Context
import android.content.SharedPreferences
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import java.util.Properties

class SSHTunnelManager(
    private val context: Context,
    private val sshUser: String,
    private val sshHost: String,
    private val sshPort: Int,
    private val sshPassword: String,
    private val remotePort: Int,
    private val localHost: String = "localhost",
    private val localPort: Int = 1080
) {
    companion object {
        private const val PREFS_NAME = "aap_prefs"
        private const val SERVER_KEY = "server_key"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    // Removed currentHost as it was causing unintended key clearing
    var onError: ((String) -> Unit)? = null
    private var session: Session? = null
    private var tunnelJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startSSHTunnel() {
        // Cancel any existing job
        tunnelJob?.cancel()

        // Start a new job for the SSH tunnel
        tunnelJob = scope.launch {
            try {
                AAPLog.append("Starting SSH tunnel to $sshHost:$sshPort for user $sshUser, forwarding remote $remotePort to local $localHost:$localPort")
                ConnectionStateHolder.setState(ConnectionState.CONNECTING) // Set state to connecting

                val jsch = JSch()
                // For key-based authentication:
                // jsch.addIdentity("/path/to/private/key")

                // Initialize session
                session = jsch.getSession(sshUser, sshHost, sshPort)

                // Password authentication
                session?.setPassword(sshPassword)

                val config = Properties()
                val storedKey = prefs.getString(SERVER_KEY, null)
                
                if (storedKey.isNullOrBlank()) {
                    // No server key stored, accept new one and store it
                    config["StrictHostKeyChecking"] = "no"
                    AAPLog.append("No server key found for $sshHost. Accepting new key.")
                } else {
                    // Server key exists, use it for strict checking
                    config["StrictHostKeyChecking"] = "yes"
                    // JSch's setKnownHosts expects a known_hosts file format.
                    // We need to provide the host along with the key.
                    val knownHostsEntry = "$sshHost $storedKey"
                    jsch.setKnownHosts(ByteArrayInputStream(knownHostsEntry.toByteArray()))
                    AAPLog.append("Using stored server key for $sshHost: $storedKey")
                }
                session?.setConfig(config)

                // Connect to the server
                session?.connect(30000) // 30 second timeout

                // If a new key was accepted, store it now
                if (storedKey.isNullOrBlank()) {
                    val hostKey = session?.hostKey
                    if (hostKey != null) {
                        val keyString = "${hostKey.type} ${hostKey.key}"
                        prefs.edit().putString(SERVER_KEY, keyString).apply()
                        AAPLog.append("Stored new server key for $sshHost: $keyString")
                    } else {
                        AAPLog.append("Warning: No host key received after connection for $sshHost.")
                    }
                }

                // Set up remote port forwarding
                session?.setPortForwardingR(remotePort, localHost, localPort)

                // Update UI state and log on success
                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel started successfully")
                    ConnectionStateHolder.setState(ConnectionState.CONNECTED) // Set state to connected
                }

                // Keep the coroutine alive to maintain the connection
                // Also implement periodic checks for connection health
                while (isActive) {
                    if (session?.isConnected != true) {
                        withContext(Dispatchers.Main) {
                            AAPLog.append("SSH connection lost, attempting reconnect...")
                            ConnectionStateHolder.setState(ConnectionState.CONNECTING) // Set state to connecting
                        }
                        reconnect()
                    }
                    delay(30000) // Check every 30 seconds
                }
            } catch (e: JSchException) {
                withContext(Dispatchers.Main) {
                    if (e.message?.contains("HostKey") == true) {
                        val storedKey = prefs.getString(SERVER_KEY, "")
                        val currentKey = session?.hostKey?.let { "${it.type} ${it.key}" } ?: "unknown"
                        AAPLog.append("SSH host key verification failed!\nStored key: $storedKey\nCurrent key: $currentKey")
                        onError?.invoke("Server key changed! Potential security issue. Clear key to accept new.")
                    } else {
                        AAPLog.append("SSH tunnel failed: ${e.message}")
                    }
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
                }
                stopSSHTunnel()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel failed: ${e.message}")
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
                }
                onError?.invoke("SSH tunnel failed: ${e.message}")
            }
        }
    }

    private suspend fun reconnect() {
        try {
            // Clean up existing session
            session?.disconnect()

            // Create new session
            val jsch = JSch()
            session = jsch.getSession(sshUser, sshHost, sshPort)
            session?.setPassword(sshPassword)

            val config = Properties()
            val storedKey = prefs.getString(SERVER_KEY, null)

            if (storedKey.isNullOrBlank()) {
                // If key was cleared or never stored, accept new one
                config["StrictHostKeyChecking"] = "no"
                AAPLog.append("No server key found for $sshHost during reconnect. Accepting new key.")
            } else {
                // Use stored key for strict checking
                config["StrictHostKeyChecking"] = "yes"
                val knownHostsEntry = "$sshHost $storedKey"
                jsch.setKnownHosts(ByteArrayInputStream(knownHostsEntry.toByteArray()))
                AAPLog.append("Using stored server key for $sshHost during reconnect: $storedKey")
            }
            session?.setConfig(config)

            // Connect and set up forwarding again
            session?.connect(30000)

            // If a new key was accepted during reconnect, store it now
            if (storedKey.isNullOrBlank()) {
                val hostKey = session?.hostKey
                if (hostKey != null) {
                    val keyString = "${hostKey.type} ${hostKey.key}"
                    prefs.edit().putString(SERVER_KEY, keyString).apply()
                    AAPLog.append("Stored new server key for $sshHost during reconnect: $keyString")
                } else {
                    AAPLog.append("Warning: No host key received after reconnect for $sshHost.")
                }
            }

            session?.setPortForwardingR(remotePort, localHost, localPort)

            withContext(Dispatchers.Main) {
                AAPLog.append("SSH tunnel reconnected successfully")
                ConnectionStateHolder.setState(ConnectionState.CONNECTED) // Set state to connected
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                AAPLog.append("Reconnection failed: ${e.message}")
                ConnectionStateHolder.setState(ConnectionState.DISCONNECTED) // Set state to disconnected on failure
            }
            onError?.invoke("Reconnection failed: ${e.message}")
            // Wait before trying again
            delay(5000) // Wait 5 seconds before next reconnect attempt (the while loop will trigger it)
        }
    }

    fun stopSSHTunnel() {
        scope.launch(Dispatchers.IO) {
            try {
                AAPLog.append("Stopping SSH tunnel...")
                ConnectionStateHolder.setState(ConnectionState.DISCONNECTING) // Set state to disconnecting

                // Cancel the tunnel monitoring job
                tunnelJob?.cancel()
                tunnelJob = null

                // Cancel port forwarding
                // session?.delPortForwardingR(remotePort) // This might not be necessary if session disconnects

                // Disconnect the session
                session?.disconnect()
                session = null

                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel stopped")
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED) // Set state to disconnected
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AAPLog.append("Failed to stop SSH tunnel: ${e.message}")
                    // State might remain DISCONNECTING or go to DISCONNECTED depending on failure
                    // Let's assume it eventually goes to DISCONNECTED
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
                }
                e.printStackTrace()
            }
        }
    }

    // Clean up resources when the component is destroyed
    fun destroy() {
        scope.cancel() // Cancel the coroutine scope
        session?.disconnect()
        session = null
        AAPLog.append("SSHTunnelManager destroyed")
    }
}

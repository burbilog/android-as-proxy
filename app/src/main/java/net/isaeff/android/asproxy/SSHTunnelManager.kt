package net.isaeff.android.asproxy

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.util.Properties

class SSHTunnelManager(
    private val sshUser: String,
    private val sshHost: String,
    private val sshPort: Int,
    private val sshPassword: String, // Consider secure storage - currently passed via Intent
    private val remotePort: Int,
    private val localHost: String = "localhost", // Default to localhost
    private val localPort: Int = 1080 // Default to 1080 for SOCKS
) {
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

                // Configure session
                val config = Properties()
                config["StrictHostKeyChecking"] = "no" // Not recommended for production
                session?.setConfig(config)

                // Connect to the server
                session?.connect(30000) // 30 second timeout

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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel failed: ${e.message}")
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED) // Set state to disconnected on failure
                }
                onError?.invoke("SSH tunnel failed: ${e.message}")
                e.printStackTrace()
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
            config["StrictHostKeyChecking"] = "no"
            session?.setConfig(config)

            // Connect and set up forwarding again
            session?.connect(30000)
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

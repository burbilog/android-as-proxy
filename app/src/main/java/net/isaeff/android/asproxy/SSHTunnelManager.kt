package net.isaeff.android.asproxy

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.util.Properties

class SSHTunnelManager {
    var onError: ((String) -> Unit)? = null
    private var session: Session? = null
    private var tunnelJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // SSH connection parameters
    private val sshUser = "tester"
    private val sshHost = "192.168.0.242"
    private val sshPort = 22
    private val sshPassword = "grogogo" // Consider secure storage

    // Port forwarding parameters
    private val remotePort = 10022
    private val localHost = "localhost"
    private val localPort = 1080

    fun startSSHTunnel() {
        // Cancel any existing job
        tunnelJob?.cancel()

        // Start a new job for the SSH tunnel
        tunnelJob = scope.launch {
            try {
                AAPLog.append("Starting SSH tunnel...")

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

                // Update UI on the main thread
                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel started")
                }

                // Keep the coroutine alive to maintain the connection
                // Also implement periodic checks for connection health
                while (isActive) {
                    if (session?.isConnected != true) {
                        withContext(Dispatchers.Main) {
                            AAPLog.append("SSH connection lost, reconnecting...")
                        }
                        reconnect()
                    }
                    delay(30000) // Check every 30 seconds
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel failed: ${e.message}")
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
                AAPLog.append("SSH tunnel reconnected")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                AAPLog.append("Reconnection failed: ${e.message}")
            }
            onError?.invoke("Reconnection failed: ${e.message}")
            // Wait before trying again
            delay(5000)
        }
    }

    fun stopSSHTunnel() {
        scope.launch(Dispatchers.IO) {
            try {
                // Cancel the tunnel monitoring job
                tunnelJob?.cancel()
                tunnelJob = null

                // Cancel port forwarding
                session?.delPortForwardingR(remotePort)

                // Disconnect the session
                session?.disconnect()
                session = null

                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel stopped")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AAPLog.append("Failed to stop SSH tunnel: ${e.message}")
                }
                e.printStackTrace()
            }
        }
    }

    // Clean up resources when the component is destroyed
    fun destroy() {
        scope.cancel()
        session?.disconnect()
        session = null
    }
}

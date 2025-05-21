package net.isaeff.android.asproxy

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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

                // Configure crypto algorithms and host key checking
                val config = Properties().also { config ->
                    val storedKey = prefs.getString(SERVER_KEY, null)
                
                // Set crypto algorithms based on Android version
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    // For older Android versions (pre-Oreo), use more compatible algorithms
                    config["kex"] = "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1"
                    config["server_host_key"] = "ssh-rsa,ssh-dss"
                    config["cipher.s2c"] = "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc"
                    config["cipher.c2s"] = "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc"
                    config["mac.s2c"] = "hmac-md5,hmac-sha1,hmac-sha2-256,hmac-sha1-96,hmac-md5-96"
                    config["mac.c2s"] = "hmac-md5,hmac-sha1,hmac-sha2-256,hmac-sha1-96,hmac-md5-96"
                    AAPLog.append("Using legacy crypto algorithms for Android ${Build.VERSION.SDK_INT}")
                } else {
                    // For newer Android versions, use modern algorithms
                    config["kex"] = "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521"
                    config["server_host_key"] = "ssh-rsa,rsa-sha2-256,rsa-sha2-512,ecdsa-sha2-nistp256"
                    config["cipher.s2c"] = "aes128-ctr,aes128-cbc,aes256-ctr,aes256-cbc,3des-ctr,3des-cbc"
                    config["cipher.c2s"] = "aes128-ctr,aes128-cbc,aes256-ctr,aes256-cbc,3des-ctr,3des-cbc"
                    config["mac.s2c"] = "hmac-sha1,hmac-sha2-256,hmac-md5"
                    config["mac.c2s"] = "hmac-sha1,hmac-sha2-256,hmac-md5"
                    AAPLog.append("Using modern crypto algorithms for Android ${Build.VERSION.SDK_INT}")
                }

                    // Configure host key checking
                    if (storedKey.isNullOrBlank()) {
                        config["StrictHostKeyChecking"] = "no"
                        AAPLog.append("No server key found for $sshHost. Accepting new key.")
                    } else {
                        config["StrictHostKeyChecking"] = "yes"
                        try {
                            val knownHostsEntry = "$sshHost $storedKey"
                            jsch.setKnownHosts(ByteArrayInputStream(knownHostsEntry.toByteArray()))
                            AAPLog.append("Using stored server key for $sshHost")
                        } catch (e: Exception) {
                            AAPLog.append("Error setting known hosts: ${e.message}")
                            config["StrictHostKeyChecking"] = "no"
                        }
                    }
                }

                // Password authentication
                session?.setPassword(sshPassword)
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
                        // Log the actual config being used
                        val configDump = config.entries.joinToString("\n") { "${it.key}=${it.value}" }
                        AAPLog.append("Current SSH config:\n$configDump")
                        // Log available algorithms
                        try {
                            val availableAlgorithms = session?.config?.let { cfg ->
                                listOf("kex", "server_host_key", "cipher.s2c", "cipher.c2s", "mac.s2c", "mac.c2s")
                                    .joinToString("\n") { alg -> "$alg=${cfg[alg]}" }
                            } ?: "No session config available"
                            AAPLog.append("Available algorithms:\n$availableAlgorithms")
                        } catch (e: Exception) {
                            AAPLog.append("Failed to get available algorithms: ${e.message}")
                        }
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

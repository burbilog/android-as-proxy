package net.isaeff.android.asproxy

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
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
    var onError: ((String) -> Unit)? = null
    private var session: Session? = null
    private var tunnelJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Make config accessible to both try and catch blocks
    private var connectConfig: Properties? = null

    fun startSSHTunnel() {
        // Cancel any existing job
        tunnelJob?.cancel()

        // Start a new job for the SSH tunnel
        tunnelJob = scope.launch {
            try {
                AAPLog.append("Starting SSH tunnel to $sshHost:$sshPort for user $sshUser, forwarding remote $remotePort to local $localHost:$localPort")
                ConnectionStateHolder.setState(ConnectionState.CONNECTING)

                val jsch = JSch()
                // For key-based authentication:
                // jsch.addIdentity("/path/to/private/key")

                // Initialize session
                session = jsch.getSession(sshUser, sshHost, sshPort)

                // Configure crypto algorithms and host key checking
                val storedKey = prefs.getString(SERVER_KEY, null)
                connectConfig = Properties().apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        put("kex", "diffie-hellman-group1-sha1,diffie-hellman-group14-sha1")
                        put("server_host_key", "ssh-rsa,ssh-dss")
                        put("cipher.s2c", "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc")
                        put("cipher.c2s", "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc,blowfish-cbc")
                        put("mac.s2c", "hmac-md5,hmac-sha1,hmac-sha2-256,hmac-sha1-96,hmac-md5-96")
                        put("mac.c2s", "hmac-md5,hmac-sha1,hmac-sha2-256,hmac-sha1-96,hmac-md5-96")
                        AAPLog.append("Using legacy crypto algorithms for Android ${Build.VERSION.SDK_INT}")
                    } else {
                        // Include EC algorithms where supported
                        put("kex", "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521")
                        put("server_host_key", "ssh-rsa,rsa-sha2-256,rsa-sha2-512,ecdsa-sha2-nistp256")
                        put("cipher.s2c", "aes128-ctr,aes128-cbc,aes256-ctr,aes256-cbc,3des-ctr,3des-cbc")
                        put("cipher.c2s", "aes128-ctr,aes128-cbc,aes256-ctr,aes256-cbc,3des-ctr,3des-cbc")
                        put("mac.s2c", "hmac-sha1,hmac-sha2-256,hmac-md5")
                        put("mac.c2s", "hmac-sha1,hmac-sha2-256,hmac-md5")
                        AAPLog.append("Using modern crypto algorithms for Android ${Build.VERSION.SDK_INT}")
                    }

                    if (storedKey.isNullOrBlank()) {
                        put("StrictHostKeyChecking", "no")
                        AAPLog.append("No server key found for $sshHost. Accepting new key.")
                    } else {
                        put("StrictHostKeyChecking", "yes")
                        try {
                            val knownHostsEntry = "$sshHost $storedKey"
                            jsch.setKnownHosts(ByteArrayInputStream(knownHostsEntry.toByteArray()))
                            AAPLog.append("Using stored server key for $sshHost")
                        } catch (e: Exception) {
                            AAPLog.append("Error setting known hosts: ${e.message}")
                            put("StrictHostKeyChecking", "no")
                        }
                    }
                }

                // Password authentication
                session?.setPassword(sshPassword)
                session?.setConfig(connectConfig)

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

                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel started successfully")
                    ConnectionStateHolder.setState(ConnectionState.CONNECTED)
                }

                while (isActive) {
                    if (session?.isConnected != true) {
                        withContext(Dispatchers.Main) {
                            AAPLog.append("SSH connection lost, attempting reconnect...")
                            ConnectionStateHolder.setState(ConnectionState.CONNECTING)
                        }
                        reconnect()
                    }
                    delay(30000)
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
                        val cfg = connectConfig
                        val configDump = cfg?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "No config available"
                        AAPLog.append("Current SSH config:\n$configDump")
                        // Log available algorithms
                        try {
                            val availableAlgorithms = session?.config?.let { sCfg ->
                                listOf("kex", "server_host_key", "cipher.s2c", "cipher.c2s", "mac.s2c", "mac.c2s")
                                    .joinToString("\n") { alg -> "$alg=${sCfg[alg]}" }
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
            session?.disconnect()

            val jsch = JSch()
            session = jsch.getSession(sshUser, sshHost, sshPort)
            session?.setPassword(sshPassword)

            val storedKey = prefs.getString(SERVER_KEY, null)
            val cfg = Properties().apply {
                if (storedKey.isNullOrBlank()) {
                    this["StrictHostKeyChecking"] = "no"
                    AAPLog.append("No server key found for $sshHost during reconnect. Accepting new key.")
                } else {
                    this["StrictHostKeyChecking"] = "yes"
                    val knownHostsEntry = "$sshHost $storedKey"
                    jsch.setKnownHosts(ByteArrayInputStream(knownHostsEntry.toByteArray()))
                    AAPLog.append("Using stored server key for $sshHost during reconnect: $storedKey")
                }
            }
            session?.setConfig(cfg)

            session?.connect(30000)

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
                ConnectionStateHolder.setState(ConnectionState.CONNECTED)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                AAPLog.append("Reconnection failed: ${e.message}")
                ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            }
            onError?.invoke("Reconnection failed: ${e.message}")
            delay(5000)
        }
    }

    fun stopSSHTunnel() {
        scope.launch(Dispatchers.IO) {
            try {
                AAPLog.append("Stopping SSH tunnel...")
                ConnectionStateHolder.setState(ConnectionState.DISCONNECTING)

                tunnelJob?.cancel()
                tunnelJob = null

                session?.disconnect()
                session = null

                withContext(Dispatchers.Main) {
                    AAPLog.append("SSH tunnel stopped")
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AAPLog.append("Failed to stop SSH tunnel: ${e.message}")
                    ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
                }
                e.printStackTrace()
            }
        }
    }

    fun destroy() {
        scope.cancel()
        session?.disconnect()
        session = null
        AAPLog.append("SSHTunnelManager destroyed")
    }
}

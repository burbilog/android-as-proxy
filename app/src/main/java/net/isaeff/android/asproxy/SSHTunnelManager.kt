package net.isaeff.android.asproxy

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.security.AlgorithmParameters
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
        private const val PREF_WORKING_PROFILE = "ssh_working_profile"
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

    // Define algorithm profiles from strongest to broadest compatibility
    private data class AlgoProfile(
        val key: String,
        val desc: String,
        val config: Properties
    )

    private fun ecAlgorithmsAvailable(): Boolean {
        return try {
            // Some emulators on old API levels lack EC AlgorithmParameters
            AlgorithmParameters.getInstance("EC")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildProfiles(jsch: JSch, storedKey: String?): List<AlgoProfile> {
        val commonHostKeyProps: (Properties) -> Unit = { p ->
            if (storedKey.isNullOrBlank()) {
                p["StrictHostKeyChecking"] = "no"
            } else {
                p["StrictHostKeyChecking"] = "yes"
                val knownHostsEntry = "$sshHost $storedKey"
                jsch.setKnownHosts(ByteArrayInputStream(knownHostsEntry.toByteArray()))
            }
            // Avoid failing on newer servers that disable MD5 MACs; list stronger first
            p["PreferredAuthentications"] = "password"
        }

        val supportsEC = ecAlgorithmsAvailable()

        // Modern EC-first profile (only if EC available)
        val modern = Properties().apply {
            put("kex", "diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha1")
            put("server_host_key", "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa")
            put("cipher.s2c", "aes256-ctr,aes128-ctr,aes256-cbc,aes128-cbc,3des-ctr,3des-cbc")
            put("cipher.c2s", "aes256-ctr,aes128-ctr,aes256-cbc,aes128-cbc,3des-ctr,3des-cbc")
            put("mac.s2c", "hmac-sha2-256,hmac-sha1,hmac-md5")
            put("mac.c2s", "hmac-sha2-256,hmac-sha1,hmac-md5")
            commonHostKeyProps(this)
        }

        // RSA-centric profile without EC
        val rsaCentric = Properties().apply {
            put("kex", "diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha1")
            put("server_host_key", "rsa-sha2-512,rsa-sha2-256,ssh-rsa")
            put("cipher.s2c", "aes256-ctr,aes128-ctr,aes256-cbc,aes128-cbc,3des-ctr,3des-cbc")
            put("cipher.c2s", "aes256-ctr,aes128-ctr,aes256-cbc,aes128-cbc,3des-ctr,3des-cbc")
            put("mac.s2c", "hmac-sha2-256,hmac-sha1,hmac-md5")
            put("mac.c2s", "hmac-sha2-256,hmac-sha1,hmac-md5")
            commonHostKeyProps(this)
        }

        // Compatibility profile for older Android and servers (no EC, safer than very legacy)
        val compat = Properties().apply {
            put("kex", "diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha1")
            put("server_host_key", "rsa-sha2-256,ssh-rsa")
            put("cipher.s2c", "aes128-ctr,aes256-ctr,3des-ctr,aes128-cbc,3des-cbc")
            put("cipher.c2s", "aes128-ctr,aes256-ctr,3des-ctr,aes128-cbc,3des-cbc")
            put("mac.s2c", "hmac-sha2-256,hmac-sha1")
            put("mac.c2s", "hmac-sha2-256,hmac-sha1")
            commonHostKeyProps(this)
        }

        // Very legacy catch-all (use only if needed)
        val legacy = Properties().apply {
            put("kex", "diffie-hellman-group14-sha1,diffie-hellman-group1-sha1")
            put("server_host_key", "ssh-rsa") // Prefer to avoid ssh-dss
            put("cipher.s2c", "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc")
            put("cipher.c2s", "aes128-ctr,aes128-cbc,3des-ctr,3des-cbc")
            put("mac.s2c", "hmac-sha1")
            put("mac.c2s", "hmac-sha1")
            commonHostKeyProps(this)
        }

        val profiles = mutableListOf<AlgoProfile>()
        val last = prefs.getString(PREF_WORKING_PROFILE, null)
        val all = buildList {
            if (supportsEC) add(AlgoProfile("modern", "Modern EC + RSA", modern))
            add(AlgoProfile("rsa", "RSA-centric", rsaCentric))
            add(AlgoProfile("compat", "Compatibility (no EC, safe)", compat))
            add(AlgoProfile("legacy", "Legacy compatibility", legacy))
        }
        if (last != null) {
            val preferred = all.find { it.key == last }
            if (preferred != null) profiles.add(preferred)
        }
        all.forEach { if (profiles.none { p -> p.key == it.key }) profiles.add(it) }
        return profiles
    }

    private fun tryConnectWithProfile(profile: AlgoProfile, jsch: JSch): Session {
        val s = jsch.getSession(sshUser, sshHost, sshPort)
        s.setPassword(sshPassword)
        s.setConfig(profile.config)
        AAPLog.append("Attempting SSH with profile '${profile.key}' (${profile.desc})")
        s.connect(30000)
        return s
    }

    private fun setupRemoteForwardingWithFallbacks(sess: Session) {
        // Ensure any previous forward is cleared first
        try {
            sess.delPortForwardingR(remotePort)
            AAPLog.append("Cleared existing remote port forwarding on $remotePort (if any)")
        } catch (_: Throwable) {
            // ignore - likely not set
        }

        val bindCandidates = listOf(
            "127.0.0.1",
            "localhost",
            // Empty host lets server default decide; some sshd configs accept this
            "",
            // As a last resort (requires GatewayPorts clientspecified or yes):
            "0.0.0.0"
        )

        var lastError: Throwable? = null
        for (bind in bindCandidates) {
            try {
                if (bind.isEmpty()) {
                    AAPLog.append("Trying remote forward: [default bind] $remotePort -> $localHost:$localPort")
                    sess.setPortForwardingR(remotePort, localHost, localPort)
                } else {
                    AAPLog.append("Trying remote forward: $bind:$remotePort -> $localHost:$localPort")
                    sess.setPortForwardingR(bind, remotePort, localHost, localPort)
                }
                AAPLog.append("Remote port forwarding established on ${if (bind.isEmpty()) "[default]" else bind}:$remotePort")
                return
            } catch (e: Throwable) {
                lastError = e
                AAPLog.append("Remote forward failed for bind '${if (bind.isEmpty()) "default" else bind}': ${e.message}")
                // If it's "address already in use", try to delete and retry once
                if (e.message?.contains("administratively prohibited") == true ||
                    e.message?.contains("remote port forwarding failed") == true ||
                    e.message?.contains("address already in use") == true
                ) {
                    try {
                        sess.delPortForwardingR(remotePort)
                        AAPLog.append("Attempted to clear remote forwarding after failure; retrying...")
                    } catch (_: Throwable) {
                        // ignore
                    }
                }
            }
        }
        throw JSchException(lastError?.message ?: "remote port forwarding failed")
    }

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

                val storedKey = prefs.getString(SERVER_KEY, null)
                val profiles = buildProfiles(jsch, storedKey)

                // Attempt connection using the profiles in order
                var connectedSession: Session? = null
                var usedProfile: AlgoProfile? = null
                var lastError: Throwable? = null
                for (p in profiles) {
                    try {
                        connectConfig = p.config
                        connectedSession = tryConnectWithProfile(p, jsch)
                        usedProfile = p
                        break
                    } catch (e: Throwable) {
                        lastError = e
                        AAPLog.append("Profile '${p.key}' failed: ${e.message}")
                    }
                }
                if (connectedSession == null) {
                    throw lastError ?: JSchException("SSH connect failed for all profiles")
                }
                session = connectedSession

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

                // Remember which profile worked
                usedProfile?.let {
                    prefs.edit().putString(PREF_WORKING_PROFILE, it.key).apply()
                    AAPLog.append("SSH connected using profile '${it.key}' (${it.desc})")
                }

                // Set up remote port forwarding with fallbacks
                session?.let { setupRemoteForwardingWithFallbacks(it) }

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
                        // Log the actual config being used to help debug
                        val cfg = connectConfig
                        val configDump = cfg?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "No config available"
                        AAPLog.append("Current SSH config:\n$configDump")
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
            val storedKey = prefs.getString(SERVER_KEY, null)
            val profiles = buildProfiles(jsch, storedKey)

            var connectedSession: Session? = null
            var usedProfile: AlgoProfile? = null
            var lastError: Throwable? = null
            for (p in profiles) {
                try {
                    connectConfig = p.config
                    val s = jsch.getSession(sshUser, sshHost, sshPort)
                    s.setPassword(sshPassword)
                    s.setConfig(p.config)
                    AAPLog.append("Reconnecting with profile '${p.key}' (${p.desc})")
                    s.connect(30000)
                    connectedSession = s
                    usedProfile = p
                    break
                } catch (e: Throwable) {
                    lastError = e
                    AAPLog.append("Reconnect profile '${p.key}' failed: ${e.message}")
                }
            }
            if (connectedSession == null) {
                throw lastError ?: JSchException("SSH reconnect failed for all profiles")
            }
            session = connectedSession

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

            // Remember which profile worked
            usedProfile?.let {
                prefs.edit().putString(PREF_WORKING_PROFILE, it.key).apply()
                AAPLog.append("SSH reconnected using profile '${it.key}' (${it.desc})")
            }

            // Re-establish remote port forwarding with fallbacks
            session?.let { setupRemoteForwardingWithFallbacks(it) }

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

                try {
                    session?.delPortForwardingR(remotePort)
                } catch (_: Throwable) {
                    // ignore
                }

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

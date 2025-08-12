# Android As Proxy (AAP)

Cross-border Internet made simple. **Android As Proxy (AAP)** turns any spare Android phone into an always-on SOCKS 5 gateway reachable from anywhere by using secure SSH *remote* port forwarding. Place the device in the country whose online services you need, give it to a relative, or rent a hosted phone — AAP makes all traffic that arrives to the SSH server emerge from that handset as if you were physically there. Ideal for listening to geo-restricted radio, streaming region-locked video, accessing ChatGPT or other AI providers blocked in your location, or bypassing state censorship.
Built entirely in Kotlin and running as a resilient foreground service, the tunnel survives doze mode, reboots and poor connectivity.

### Typical use cases
- Listen to Japan-only radio while living in Europe
- Watch domestic sports streams when traveling abroad
- Reach ChatGPT, Claude or other LLMs from within restrictive networks
- Provide relatives in censored regions with an uncensored connection

### Why a *physical* handset instead of a VPS?
Commercial VPS and cloud ranges (Hetzner, Vultr, DigitalOcean, AWS, etc.) are widely fingerprinted and routinely blocked by content owners and services such as Wikipedia, YouTube, ChatGPT, etc. Traffic coming from these ASNs is assumed to be “datacenter” and untrusted.
By tunnelling through an ordinary Android phone attached to a consumer ISP or mobile carrier you appear as a genuine residential user, bypassing many of these network-wide blocks while still retaining full SSH-grade encryption.

## ⚠️ Power & Device Suitability

AAP runs a **persistent foreground service** to keep the SSH session and SOCKS proxy alive.  
This prevents Android from entering doze mode but **significantly increases power
consumption**. Deploy the app on a spare handset, TV box, or any always-powered
device—ideally one that will remain plugged in and unattended.  
**Do not** install AAP on your daily driver phone unless you fully understand and
accept the battery and hardware wear implications.
## Features

- Establishes persistent SOCKS 5 proxy via SSH remote forwarding (`-R`)  
- Automatic algorithm profile negotiation for maximum compatibility, see [`SSHTunnelManager`](app/src/main/java/net/isaeff/android/asproxy/SSHTunnelManager.kt:14)  
- Runs as an Android 14-compatible foreground service with special-use flag  
- Real-time connection state broadcast through [`ConnectionStateHolder`](app/src/main/java/net/isaeff/android/asproxy/ConnectionStateHolder.kt:1)  
- On-device log viewer (`LogViewActivity`) for troubleshooting  
- Stores and verifies host keys (StrictHostKeyChecking)  
- Kotlin Coroutines for non-blocking IO  

## How it works

1. The user provides SSH credentials (username, host, port, password and remote bind port).  
2. [`SSHTunnelManager`](app/src/main/java/net/isaeff/android/asproxy/SSHTunnelManager.kt:14) tries a series of algorithm profiles (**modern**, **rsa**, **compat**, etc.) to establish the session using JSch.  
3. Once connected it requests *remote* port forwarding so the **server** listens on `remotePort` and forwards traffic back to the handset’s local SOCKS proxy running on `localhost:1080`.  
4. The proxy itself is implemented by [`SocksForegroundService`](app/src/main/java/net/isaeff/android/asproxy/SocksForegroundService.kt:1) and remains active as a foreground service, satisfying Android background execution limits.  
5. Status is reflected in a persistent notification and can be consumed by other components through `ConnectionStateHolder`.  

## Screenshots

*Coming soon – feel free to open a PR with screenshots!*

## Build & Install

1. Clone the repo  
```bash
git clone https://github.com/burbilog/android-as-proxy.git
cd android-as-proxy
```
2. Open the project in **Android Studio Hedgehog** (or newer) or build from CLI:  
```bash
./gradlew assembleRelease
```
3. Sign the generated APK located under `app/build/outputs/apk/release/`.  
4. Install on your device:  
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Minimum Requirements

- Android 7.0 (API 24)  
- OpenSSH server you control (or any server that allows remote port forwarding)  

### Runtime Permissions

The app requests:  

- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE` to keep the proxy alive.  
- `POST_NOTIFICATIONS` (Android 13+) for status notifications.  
- `INTERNET` to establish the tunnel.  

## Usage

1. Launch **AAP** on the Android phone that will reside in the target country.
2. Fill in SSH host, user, authentication method, and choose an unused **Remote port** (e.g. `2222`).
3. Tap *Start*. When the notification switches to **Connected** the SSH server is now listening on that remote port and forwarding into the phone’s local SOCKS 5 instance.
4. From your **desktop/laptop** establish the secondary tunnel detailed below ([Accessing the proxy from your computer](#accessing-the-proxy-from-your-computer)) *or*—if your client program runs directly on the SSH server—configure it to use `localhost:<REMOTE_PORT>` as a SOCKS 5 proxy.

Logs can be inspected via *Overflow → View logs*. This screen tail-streams the internal [`AAPLog`](app/src/main/java/net/isaeff/android/asproxy/AAPLog.kt:1) buffer for troubleshooting.

### Accessing the proxy from your computer

After **AAP** reports *Connected*, the SOCKS 5 service is listening on the **SSH server** (at the *remote port* you configured), not directly on the phone.  
To consume it from your desktop or laptop open a second, **local** SSH tunnel that bridges your machine to that remote bind port.

#### Linux / macOS

```bash
ssh -N -L 1080:localhost:<REMOTE_PORT> <USER>@<SERVER_IP>;
```

• **`<REMOTE_PORT>`** – the same remote port configured in AAP  
• **`1080`** – any free local port (1080 is conventional for SOCKS proxies)

Keep this shell running. All applications that use the SOCKS 5 proxy `localhost:1080` will now exit through the Android phone in the target country.

#### Windows (PuTTY)

1. Launch **PuTTY** and enter the server’s hostname/IP under *Session*.  
2. Go to *Connection → SSH → Tunnels* and add a new **Local** forwarding:  
   * **Source port**: `1080`  
   * **Destination**: `localhost:<REMOTE_PORT>`  
   * **Type**: leave **Local** (do **not** pick *Dynamic*).  
3. Click *Add*, return to *Session* and *Open* the connection, then authenticate.  
4. Keep the PuTTY window open while you browse.

Finally, configure your browser or other tools to use **SOCKS 5 proxy `localhost:1080`**. All traffic will traverse:

```
your PC ──► ssh -L 1080 … ──► SSH server ──► remotePort ──► phone ──► Internet
```
## Contributing

Pull requests are welcome. For major changes please open an issue first to discuss what you would like to change. Make sure to run `./gradlew lint ktlintFormat detekt` before submitting.

## Roadmap

- [ ] SSH keys auth in addition to password auth
- [ ] Settings screen for multiple profiles  
- [ ] Public build on F-Droid  
- [ ] Localization (RU, etc)  

## License

Android As Proxy is free software: you can redistribute it and/or modify  
it under the terms of the **GNU General Public License v3.0** as published by  
the Free Software Foundation.

This program is distributed in the hope that it will be useful,  
but **WITHOUT ANY WARRANTY**; without even the implied warranty of  
**MERCHANTABILITY** or **FITNESS FOR A PARTICULAR PURPOSE**.  See the  
GNU General Public License for more details.

You should have received a copy of the GNU General Public License  
along with this program. If not, see <https://www.gnu.org/licenses/>.

## Author

Roman V. Isaev <rm@isaeff.net>

---
*Happy tunneling!*

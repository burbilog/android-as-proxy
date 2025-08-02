package net.isaeff.android.asproxy

/**
 * Compile-time e-mail configuration.
 *
 * All values are constants so they are baked into the APK at build time.
 * Replace the placeholder values during your build or CI pipeline
 * if you don’t want them committed to VCS.
 */
object EmailConfig {
    /** SMTP server IP address (no DNS lookup to keep it simple). */
    const val SMTP_HOST = "168.235.88.175"           // TODO: replace with real IP
    /** SMTP port (usually 25, 465, or 587). */
    const val SMTP_PORT = "25"
    // Optional authentication—leave blank when server is open-relay for
    // internal bug reports.
    const val USERNAME = ""
    const val PASSWORD = ""

    /** E-mail address that appears in the “From:” header. */
    const val SENDER = "noreply@isaeff.net"

    /** Recipient address for log reports. */
    const val RECIPIENT = "rm@isaeff.net"

    /** Domain sent in SMTP HELO/EHLO to satisfy Postfix's FQDN requirement. */
    const val HELO_DOMAIN = "ns.isaeff.net"
}
package net.isaeff.android.asproxy

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Utility responsible for sending e-mails in a background thread.
 *
 * All configuration is read from [EmailConfig].
 * The JavaMail “android-mail” + “android-activation” artifacts (added
 * in build.gradle) provide the required classes on Android.
 */
object EmailSender {

    /**
     * Sends [logText] to [EmailConfig.RECIPIENT] asynchronously.
     *
     * The operation is non-blocking for the UI thread.  Progress is
     * reported to [AAPLog] so it becomes visible in the in-app log.
     */
    fun sendAsync(logText: String) {
        AAPLog.append("EmailSender: start sending report…")
        Thread {
            try {
                sendSync(logText)
                AAPLog.append("EmailSender: report sent successfully.")
            } catch (t: Throwable) {
                AAPLog.append("EmailSender: failed to send report – ${t.message}")
            }
        }.start()
    }

    /**
     * Performs a blocking SMTP send.
     *
     * NOTE: Do **not** call this directly from the UI thread.
     */
    private fun sendSync(body: String) {
        // --- Configure JavaMail session (no authentication) -----------------
        val props = Properties().apply {
            put("mail.smtp.host", EmailConfig.SMTP_HOST)
            put("mail.smtp.port", EmailConfig.SMTP_PORT)
            // Explicitly disable authentication; server accepts unauthenticated mail.
            put("mail.smtp.auth", "false")
            // Custom HELO/EHLO hostname required by server
            put("mail.smtp.localhost", EmailConfig.HELO_DOMAIN)
        }
 
        // Session without Authenticator because no credentials are needed
        val session = Session.getInstance(props, null)

        // --- Compose message ------------------------------------------------
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(EmailConfig.SENDER))
            setRecipient(Message.RecipientType.TO, InternetAddress(EmailConfig.RECIPIENT))
            subject = "AAP debug report"
            setText(body)
        }

        // --- Send -----------------------------------------------------------
        Transport.send(msg)
    }
}
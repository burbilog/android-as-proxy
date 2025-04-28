package net.isaeff.android.asproxy

object AAPLog {
    private val logBuilder = StringBuilder()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun append(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logBuilder.append("[$timestamp] $message\n")
        notifyListeners()
    }

    @Synchronized
    fun getLog(): String = logBuilder.toString()

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }
}

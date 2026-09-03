package com.dk.zopf.runtime

data class NotificationAction(
    val id: String,
    val label: String,
)

interface NotificationHandle {
    fun cancel()
}

interface Notifier {
    fun post(notification: RunNotification)

    fun ask(
        notification: RunNotification,
        timeoutSeconds: Long,
        onAnswer: (String) -> Unit,
    ): NotificationHandle

    fun withdraw(key: String)
}

object SilentNotifier : Notifier {
    override fun post(notification: RunNotification) = Unit

    override fun ask(
        notification: RunNotification,
        timeoutSeconds: Long,
        onAnswer: (String) -> Unit,
    ): NotificationHandle = NoHandle

    override fun withdraw(key: String) = Unit

    private object NoHandle : NotificationHandle {
        override fun cancel() = Unit
    }
}

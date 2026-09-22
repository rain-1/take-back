package com.takeback.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Attachment
import com.takeback.app.net.ChannelMessage
import com.takeback.app.net.GroupMessage
import com.takeback.app.net.Mentions
import com.takeback.app.net.Message
import java.util.concurrent.TimeUnit

/**
 * Transport-neutral entry point for background notifications.
 *
 * WorkManager supplies the wake-ups today. A future FCM or UnifiedPush receiver
 * only needs to call [wake] -- message discovery, de-duplication and rendering
 * remain exactly the same.
 */
object BackgroundNotifications {
    private const val PERIODIC_WORK = "takeback-notification-poll"
    private const val IMMEDIATE_WORK = "takeback-notification-sync"
    private const val CONFIG_PREFS = "tb_config"
    private const val ENABLED_KEY = "stay_connected" // retain the old setting on upgrade

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (enabled) configure(context) else cancel(context)
    }

    /** Ensure one battery-aware periodic poll exists, and perform a short sync now. */
    fun configure(context: Context) {
        if (!enabled(context) || !ApiClient.hasSession()) {
            cancel(context)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<NotificationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
        wake(context)
    }

    /**
     * Ask for one short sync. Push transports should call this on receipt;
     * KEEP coalesces a burst of wake-ups into one server fetch.
     */
    fun wake(context: Context) {
        if (!enabled(context) || !ApiClient.hasSession()) return
        val request = OneTimeWorkRequestBuilder<NotificationSyncWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
    }

    /** Forget cursors when the account/server changes; old ids must not leak across accounts. */
    fun reset(context: Context) {
        cancel(context)
        context.getSharedPreferences(NotificationState.PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** Live WebSocket delivery advances the same cursors used by background sync. */
    fun recordMessage(context: Context, kind: String, targetId: Long, messageId: Long) {
        val userId = ApiClient.myId
        if (userId == 0L) return
        NotificationState(context).record("${ApiClient.base}|$userId", kind, targetId, messageId)
    }

    fun recordSeen(context: Context, kind: String, value: Long) {
        val userId = ApiClient.myId
        if (userId == 0L) return
        val state = NotificationState(context)
        val scope = "${ApiClient.base}|$userId"
        state.seen(scope, kind, state.seen(scope, kind) + value.toString())
    }
}

class NotificationSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ApiClient.init(applicationContext)
        if (!BackgroundNotifications.enabled(applicationContext) || !ApiClient.hasSession()) {
            return Result.success()
        }
        return try {
            NotificationSync(applicationContext).run()
            Result.success()
        } catch (error: Exception) {
            android.util.Log.w("take-back", "background notification sync failed", error)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

/** Fetches durable unread state. It knows nothing about what caused the wake-up. */
private class NotificationSync(private val context: Context) {
    private val state = NotificationState(context)

    suspend fun run() {
        val me = ApiClient.me()
        val scope = "${ApiClient.base}|${me.id}"

        val friends = ApiClient.friends()
        val incomingRequests = friends
            .filter { it.status == "pending" && it.direction == "incoming" }
            .associate { it.user.id.toString() to it.user.nick }
        val seenRequests = state.seen(scope, "friend_requests")
        incomingRequests.filterKeys { it !in seenRequests }.forEach { (id, nick) ->
            NotificationCenter.friendRequest(context, id.toLong(), nick)
        }
        state.seen(scope, "friend_requests", incomingRequests.keys)

        friends.filter { it.status == "accepted" && it.unread > 0 }.forEach { friend ->
            val latest = ApiClient.conversation(friend.user.id)
                .filter { it.senderId == friend.user.id && it.deletedAt == 0L }
                .maxByOrNull { it.id } ?: return@forEach
            if (state.advance(scope, "dm", friend.user.id, latest.id)) {
                val mentioned = Mentions.mentions(latest.body, me.nick)
                if (mentioned) Mentions.mark(Mentions.dmKey(friend.user.id))
                NotificationCenter.dm(context, latest, friend.user.nick, mentioned)
            } else {
                state.record(scope, "dm", friend.user.id, latest.id)
            }
        }

        val invites = ApiClient.groupInvites()
        val inviteIds = invites.map { it.groupId.toString() }.toSet()
        val seenInvites = state.seen(scope, "group_invites")
        invites.filter { it.groupId.toString() !in seenInvites }.forEach {
            NotificationCenter.groupInvite(context, it.groupId, it.groupName, it.invitedBy)
        }
        state.seen(scope, "group_invites", inviteIds)

        ApiClient.groups().filter { it.unread > 0 }.forEach { group ->
            val latest = ApiClient.groupConversation(group.id)
                .filter { it.senderId != me.id && it.deletedAt == 0L }
                .maxByOrNull { it.id } ?: return@forEach
            if (state.advance(scope, "group", group.id, latest.id)) {
                val mentioned = Mentions.mentions(latest.body, me.nick)
                if (mentioned) Mentions.mark(Mentions.groupKey(group.id))
                NotificationCenter.group(context, latest, group.name, mentioned)
            } else {
                state.record(scope, "group", group.id, latest.id)
            }
        }

        ApiClient.servers().filter { it.unread > 0 }.forEach { server ->
            ApiClient.channels(server.id)
                .filter { it.kind == "text" && it.unread > 0 }
                .forEach channelLoop@ { channel ->
                    val latest = ApiClient.channelConversation(channel.id)
                        .filter { it.senderId != me.id && it.deletedAt == 0L }
                        .maxByOrNull { it.id } ?: return@channelLoop
                    if (state.advance(scope, "channel", channel.id, latest.id)) {
                        val mentioned = Mentions.mentions(latest.body, me.nick)
                        if (mentioned) Mentions.markChannel(server.id, channel.id)
                        NotificationCenter.channel(context, latest, server.name, mentioned)
                    } else {
                        state.record(scope, "channel", channel.id, latest.id)
                    }
                }
        }
    }
}

/** Local high-water marks make periodic and push-triggered syncs idempotent. */
private class NotificationState(context: Context) {
    companion object {
        const val PREFS = "tb_notification_sync"
        private val lock = Any()
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(scope: String, kind: String, id: Long = 0) =
        "$scope:$kind:$id"

    fun advance(scope: String, kind: String, id: Long, messageId: Long): Boolean =
        synchronized(lock) {
            val k = key(scope, kind, id)
            val old = prefs.getLong(k, 0)
            if (messageId <= old) return@synchronized false
            prefs.edit().putLong(k, messageId).apply()
            true
        }

    fun record(scope: String, kind: String, id: Long, messageId: Long) {
        advance(scope, kind, id, messageId)
    }

    fun seen(scope: String, kind: String): Set<String> =
        prefs.getStringSet(key(scope, kind), emptySet())?.toSet() ?: emptySet()

    fun seen(scope: String, kind: String, values: Set<String>) {
        prefs.edit().putStringSet(key(scope, kind), values.toSet()).apply()
    }
}

/** Shared notification rendering used by live WebSocket events and background sync. */
object NotificationCenter {
    private const val CHANNEL = "takeback_events"
    private const val NOTIF_FRIEND = 1001
    private const val NOTIF_MESSAGE_BASE = 2000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Messages & friends", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun friendRequest(context: Context, userId: Long, nick: String) =
        post(context, NOTIF_FRIEND + userId.toInt(), "Friend request", "$nick wants to be your friend")

    fun groupInvite(context: Context, groupId: Long, name: String, by: String) =
        post(context, NOTIF_FRIEND + 100000 + groupId.toInt(), "Group invite", "$by invited you to $name")

    fun dm(context: Context, message: Message, nick: String, mentioned: Boolean) =
        post(context, NOTIF_MESSAGE_BASE + message.senderId.toInt(),
            if (mentioned) "$nick mentioned you" else "Message from $nick",
            preview(message.body, message.attachment),
            Intent(context, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_FRIEND_ID, message.senderId)
                .putExtra(ChatActivity.EXTRA_FRIEND_NICK, nick))

    fun group(context: Context, message: GroupMessage, name: String, mentioned: Boolean) =
        post(context, NOTIF_MESSAGE_BASE + 100000 + message.groupId.toInt(),
            if (mentioned) "You were mentioned in $name" else "Message in $name",
            preview(message.body, message.attachment),
            Intent(context, GroupChatActivity::class.java)
                .putExtra(GroupChatActivity.EXTRA_GROUP_ID, message.groupId)
                .putExtra(GroupChatActivity.EXTRA_GROUP_NAME, name))

    fun channel(context: Context, message: ChannelMessage, server: String, mentioned: Boolean) =
        post(context, NOTIF_MESSAGE_BASE + 200000 + message.channelId.toInt(),
            if (mentioned) "You were mentioned in $server" else "Message in $server",
            preview(message.body, message.attachment),
            Intent(context, ChannelChatActivity::class.java)
                .putExtra(ChannelChatActivity.EXTRA_CHANNEL_ID, message.channelId)
                .putExtra(ChannelChatActivity.EXTRA_SERVER_ID, message.serverId)
                .putExtra(ChannelChatActivity.EXTRA_SERVER_NAME, server))

    fun clearDm(context: Context, friendId: Long) =
        cancel(context, NOTIF_MESSAGE_BASE + friendId.toInt())
    fun clearGroup(context: Context, groupId: Long) =
        cancel(context, NOTIF_MESSAGE_BASE + 100000 + groupId.toInt())
    fun clearChannel(context: Context, channelId: Long) =
        cancel(context, NOTIF_MESSAGE_BASE + 200000 + channelId.toInt())

    private fun preview(body: String, attachment: Attachment?): String =
        if (body.isNotEmpty()) body.take(80) else when (attachment?.kind) {
            "image" -> "📷 image"
            "video" -> "🎬 ${attachment.name}"
            "audio" -> "🎵 ${attachment.name}"
            null -> "message"
            else -> "📎 ${attachment.name}"
        }

    private fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    private fun post(context: Context, id: Int, title: String, text: String, opens: Intent? = null) {
        createChannel(context)
        val stack = TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, HomeActivity::class.java))
        if (opens != null) stack.addNextIntent(opens)
        val tap = stack.getPendingIntent(id,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}

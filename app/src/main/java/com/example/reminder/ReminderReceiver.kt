package com.example.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "personal_library_reminders"
        const val CHANNEL_NAME = "Memora Reminders"
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_ITEM_TITLE = "extra_item_title"
        const val EXTRA_REMINDER_TIME = "extra_reminder_time"
        const val ACTION_COMPLETE = "com.example.reminder.ACTION_COMPLETE"
        const val ACTION_SNOOZE = "com.example.reminder.ACTION_SNOOZE"

        private val notificationPostLock = Any()

        private fun reminderBuilder(context: Context, open: PendingIntent?) =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_memora_notification)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

        private fun protectedReminder(context: Context, open: PendingIntent?) = reminderBuilder(context, open)
            .setContentTitle("Memora Reminder")
            .setContentText("You have a protected item reminder")

        internal fun protectPostedNotifications(context: Context) = synchronized(notificationPostLock) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Keep already-delivered reminders reachable, but replace any private content and actions.
            manager.activeNotifications.filter { it.notification.channelId == CHANNEL_ID }.forEach { posted ->
                manager.notify(posted.tag, posted.id, protectedReminder(context, posted.notification.contentIntent)
                    .setOnlyAlertOnce(true).build())
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(EXTRA_ITEM_ID) == null) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleIntent(context, intent, LibraryRepository.getInstance(context))
            } finally {
                pending.finish()
            }
        }
    }

    internal suspend fun handleIntent(context: Context, intent: Intent, repository: LibraryRepository) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val action = intent.action
        val appLockPrefs = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
        val isMutation = action == ACTION_COMPLETE || action == ACTION_SNOOZE
        // Old notification PendingIntents remain valid after App Lock is enabled.
        if (isMutation && appLockPrefs.getBoolean("lock_enabled", false)) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val item = repository.dao.getItemById(itemId)
        val reminder = repository.dao.getReminderByItemId(itemId)
        if (!ReminderScheduler.isActiveSource(item, reminder)) {
            ReminderScheduler(context).cancel(itemId)
            cancelNotification(notificationManager, itemId)
            return
        }
        val current = requireNotNull(reminder)
        // An old delivered alarm/action must not mutate or announce a replacement reminder.
        if (intent.hasExtra(EXTRA_REMINDER_TIME) && intent.getLongExtra(EXTRA_REMINDER_TIME, Long.MIN_VALUE) != current.dueMillis) return
        // Legacy notifications lack a generation timestamp; their actions cannot affect a future replacement.
        if (current.dueMillis > System.currentTimeMillis()) return
        if (isMutation && appLockPrefs.getBoolean("lock_enabled", false)) return

        if (action == ACTION_COMPLETE) {
            repository.completeReminder(itemId)
            ReminderScheduler(context).cancel(itemId)
            cancelNotification(notificationManager, itemId)
            return
        }

        if (action == ACTION_SNOOZE) {
            val scheduler = ReminderScheduler(context)
            val snoozeUntil = System.currentTimeMillis() + (15 * 60 * 1000L)
            repository.snoozeReminder(itemId, snoozeUntil)
            repository.dao.getItemById(itemId)?.let { latest ->
                scheduler.scheduleRestored(latest, repository.dao.getReminderByItemId(itemId))
            }
            cancelNotification(notificationManager, itemId)
            return
        }

        // Standard alarm trigger: build and show reminder notification
        if (!ReminderScheduler(context).notificationsEnabled()) return
        val itemTitle = requireNotNull(item).title

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for saved Memora items"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            data = ReminderScheduler.itemUri(itemId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            itemId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Serialize posting with lock-enable redaction so a concurrent alarm cannot leave a private notification behind.
        synchronized(notificationPostLock) {
            if (appLockPrefs.getBoolean("lock_enabled", false)) {
                notificationManager.notify(itemId, 0, protectedReminder(context, pendingIntent).build())
                return
            }
            // Snooze Intent (15 minutes)
            val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
                this.action = ACTION_SNOOZE
                data = ReminderScheduler.itemUri(itemId)
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_ITEM_TITLE, itemTitle)
                putExtra(EXTRA_REMINDER_TIME, current.dueMillis)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                itemId.hashCode() + 1,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Complete Intent
            val completeIntent = Intent(context, ReminderReceiver::class.java).apply {
                this.action = ACTION_COMPLETE
                data = ReminderScheduler.itemUri(itemId)
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_REMINDER_TIME, current.dueMillis)
            }
            val completePendingIntent = PendingIntent.getBroadcast(
                context,
                itemId.hashCode() + 2,
                completeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = reminderBuilder(context, pendingIntent)
                .setContentTitle("Reminder: $itemTitle")
                .setContentText("Tap to view this saved item in Memora")
                .addAction(android.R.drawable.ic_menu_recent_history, "Snooze 15m", snoozePendingIntent)
                .addAction(android.R.drawable.checkbox_on_background, "Complete", completePendingIntent)
            notificationManager.notify(itemId, 0, notification.build())
        }
    }

    private fun cancelNotification(manager: NotificationManager, itemId: String) {
        manager.cancel(itemId, 0)
        manager.cancel(itemId.hashCode()) // Retire notifications created before item-specific tags.
    }
}

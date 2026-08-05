package com.ghostify.background

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import timber.log.Timber

/**
 * Default Media3 notification provider + a guaranteed **Stop** button (T-097/T-100).
 *
 * Media3's [DefaultMediaNotificationProvider] already owns foregrounding,
 * channel creation, the standard previous/play-pause/next buttons, and dismissal
 * when playback goes idle. The only thing it doesn't guarantee is an explicit
 * stop/teardown affordance, so we append a `STOP` command button whose
 * `setPlayerCommand(Player.COMMAND_STOP)` tears the player down; the service's
 * idle listener then stops the foreground service (no zombie notification).
 */
class PlaybackNotificationProvider(
    context: Context,
    smallIconResId: Int,
) : DefaultMediaNotificationProvider(context) {

    init {
        setSmallIcon(smallIconResId)
    }

    override fun getMediaButtons(
        session: MediaSession,
        commands: Player.Commands,
        commandButtons: com.google.common.collect.ImmutableList<CommandButton>,
        showCompact: Boolean,
    ): com.google.common.collect.ImmutableList<CommandButton> {
        Timber.i("PlaybackNotificationProvider.getMediaButtons: START")
        val defaults = super.getMediaButtons(session, commands, commandButtons, showCompact)
        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setPlayerCommand(Player.COMMAND_STOP)
            .setDisplayName(STOP_LABEL)
            .build()
        val result = com.google.common.collect.ImmutableList.builder<CommandButton>()
            .addAll(defaults)
            .add(stopButton)
            .build()
        Timber.i("PlaybackNotificationProvider.getMediaButtons: returning ${result.size} buttons")
        return result
    }

    companion object {
        private const val STOP_LABEL = "Stop"
    }
}

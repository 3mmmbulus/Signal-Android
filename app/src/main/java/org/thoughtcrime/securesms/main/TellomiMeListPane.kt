/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.SignalIcons
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.avatar.AvatarImage
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.profiles.manage.EditProfileActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.rememberRecipientField
import org.signal.appsettings.R as AppSettingsR
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi（tellomi/tellomi#1355）：底栏「我」。iOS 那边直接放设置页（`AppSettingsViewController`）；
 * 安卓的设置页靠 [AppSettingsActivity] 自己的导航图，不能嵌进主界面，所以这里是一张入口页：
 * 顶上是自己的头像和名字（点开编辑资料），下面几行直接打开现有设置里的对应页面，最后一行打开完整设置。
 * 这里不新做任何设置项，全部跳到原来的页面。
 */
@Composable
fun TellomiMeListPane(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val selfId = remember { Recipient.self().id }
  val self by rememberRecipientField(selfId) { this }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier
        .fillMaxWidth()
        .clickable { context.startActivity(EditProfileActivity.getIntent(context)) }
        .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
      AvatarImage(
        recipient = self,
        modifier = Modifier
          .clip(CircleShape)
          .size(64.dp)
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = self.getDisplayName(context),
          style = MaterialTheme.typography.titleLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        val username = self.username.orElse(null)
        if (username != null) {
          Text(
            text = username,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }

    Dividers.Default()

    MeRow(context, stringResource(AppSettingsR.string.AccountSettingsFragment__account), painterResource(CoreUiR.drawable.symbol_person_circle_24)) { AppSettingsActivity.account(it) }
    MeRow(context, stringResource(R.string.preferences__linked_devices), painterResource(CoreUiR.drawable.symbol_devices_24)) { AppSettingsActivity.linkedDevices(it) }
    MeRow(context, stringResource(R.string.preferences_chats__chats), painterResource(R.drawable.symbol_chat_24)) { AppSettingsActivity.chats(it) }
    MeRow(context, stringResource(R.string.preferences__notifications), painterResource(R.drawable.symbol_bell_24)) { AppSettingsActivity.notifications(it) }
    MeRow(context, stringResource(R.string.preferences__privacy), SignalIcons.Lock.painter) { AppSettingsActivity.privacy(it) }

    Dividers.Default()

    MeRow(context, stringResource(R.string.preferences__help), painterResource(R.drawable.symbol_help_24)) { AppSettingsActivity.help(it) }
    MeRow(context, stringResource(R.string.text_secure_normal__menu_settings), painterResource(CoreUiR.drawable.symbol_settings_android_24)) { AppSettingsActivity.home(it) }
  }
}

@Composable
private fun MeRow(
  context: Context,
  text: String,
  icon: androidx.compose.ui.graphics.painter.Painter,
  intent: (Context) -> Intent
) {
  Rows.TextRow(
    text = text,
    icon = icon,
    onClick = { context.startActivity(intent(context)) }
  )
}

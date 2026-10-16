/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.Snackbars
import org.signal.glide.compose.GlideImage
import org.thoughtcrime.securesms.R
import org.signal.core.ui.R as CoreUiR

object FeedReportTestTags {
  const val NOT_INTERESTED = "feed_report_not_interested"
  const val SUBMIT = "feed_report_submit"
  const val RELATIONSHIP = "feed_report_relationship"
}

/**
 * 举报页（第三节第 32 条）。两步：
 * 1. 选原因：最上面先分流「只是不想看到」（走不感兴趣，不算举报）；下面四组，每项一句说明。
 * 2. 补充：紧急类先提示报警；诈骗和「我的权益」要截图，「我的权益」要写和被侵权人的关系；大陆区附 12377。
 */
@Composable
fun FeedReportScreen(
  state: FeedReportState,
  canHideInstead: Boolean,
  onBack: () -> Unit,
  onNotInterested: () -> Unit,
  onPick: (FeedReportReason) -> Unit,
  onBackToReasons: () -> Unit,
  onDetailChanged: (String) -> Unit,
  onRelationshipChanged: (String) -> Unit,
  onEvidenceAdded: (List<Uri>) -> Unit,
  onEvidenceRemoved: (Uri) -> Unit,
  onSubmit: () -> Unit,
  onOpenHotline: () -> Unit,
  onErrorShown: () -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val gone = stringResource(R.string.TellomiFeedReport__gone)
  val failed = stringResource(R.string.TellomiFeed__action_failed)
  LaunchedEffect(state.error) {
    val error = state.error ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(if (error == FeedReportState.Error.GONE) gone else failed)
    onErrorShown()
  }

  val reason = state.reason
  BackHandler(enabled = reason != null, onBack = onBackToReasons)

  Scaffolds.Settings(
    title = if (reason == null) stringResource(R.string.TellomiFeedReport__title) else stringResource(reason.label),
    onNavigationClick = if (reason == null) onBack else onBackToReasons,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    if (reason == null) {
      ReasonList(
        state = state,
        canHideInstead = canHideInstead,
        onNotInterested = onNotInterested,
        onPick = onPick,
        onOpenHotline = onOpenHotline,
        modifier = Modifier.padding(padding).fillMaxSize()
      )
    } else {
      Details(
        state = state,
        reason = reason,
        onDetailChanged = onDetailChanged,
        onRelationshipChanged = onRelationshipChanged,
        onEvidenceAdded = onEvidenceAdded,
        onEvidenceRemoved = onEvidenceRemoved,
        onSubmit = onSubmit,
        onOpenHotline = onOpenHotline,
        modifier = Modifier.padding(padding).fillMaxSize()
      )
    }
  }
}

@Composable
private fun ReasonList(
  state: FeedReportState,
  canHideInstead: Boolean,
  onNotInterested: () -> Unit,
  onPick: (FeedReportReason) -> Unit,
  onOpenHotline: () -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(modifier = modifier) {
    if (canHideInstead) {
      item(key = "not-interested") {
        ReasonRow(
          title = stringResource(R.string.TellomiFeedReport__just_hide),
          description = stringResource(R.string.TellomiFeedReport__just_hide_desc),
          onClick = onNotInterested,
          modifier = Modifier.testTag(FeedReportTestTags.NOT_INTERESTED)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      }
    }

    state.groups.forEach { group ->
      item(key = "group-${group.code}") {
        Text(
          text = stringResource(group.label),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
        )
      }
      items(items = group.reasons, key = { "${it.group}-${it.code}" }) { reason ->
        ReasonRow(title = stringResource(reason.label), description = stringResource(reason.description), onClick = { onPick(reason) })
      }
    }

    if (state.showMainlandHotline) {
      item(key = "hotline") { Hotline(onOpenHotline = onOpenHotline, modifier = Modifier.padding(24.dp)) }
    }
  }
}

@Composable
private fun ReasonRow(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 10.dp)
  ) {
    Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun Details(
  state: FeedReportState,
  reason: FeedReportReason,
  onDetailChanged: (String) -> Unit,
  onRelationshipChanged: (String) -> Unit,
  onEvidenceAdded: (List<Uri>) -> Unit,
  onEvidenceRemoved: (Uri) -> Unit,
  onSubmit: () -> Unit,
  onOpenHotline: () -> Unit,
  modifier: Modifier = Modifier
) {
  val room = FeedReportReason.MAX_EVIDENCE - state.evidence.size
  val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = room.coerceAtLeast(2))) { onEvidenceAdded(it) }
  val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onEvidenceAdded(listOf(uri)) }

  Column(
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .navigationBarsPadding()
      .imePadding()
      .padding(horizontal = 24.dp, vertical = 8.dp)
  ) {
    Text(text = stringResource(reason.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (reason.isEmergency) {
      Text(
        text = stringResource(if (state.showMainlandHotline) R.string.TellomiFeedReport__emergency_cn else R.string.TellomiFeedReport__emergency),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.errorContainer)
          .padding(12.dp)
      )
    }

    if (reason.needsRelationship) {
      OutlinedTextField(
        value = state.relationship,
        onValueChange = onRelationshipChanged,
        label = { Text(stringResource(R.string.TellomiFeedReport__relationship)) },
        placeholder = { Text(stringResource(R.string.TellomiFeedReport__relationship_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(FeedReportTestTags.RELATIONSHIP)
      )
    }

    OutlinedTextField(
      value = state.detail,
      onValueChange = onDetailChanged,
      label = { Text(stringResource(R.string.TellomiFeedReport__detail)) },
      minLines = 3,
      maxLines = 6,
      supportingText = { Text("${state.detail.codePointCount(0, state.detail.length)}/${FeedReportReason.MAX_DETAIL_CHARS}") },
      modifier = Modifier.fillMaxWidth()
    )

    if (reason.needsEvidence) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.TellomiFeedReport__evidence), style = MaterialTheme.typography.titleSmall)
        Text(
          text = stringResource(R.string.TellomiFeedReport__evidence_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(items = state.evidence, key = { it.toString() }) { uri ->
            Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
              GlideImage(model = uri, modifier = Modifier.matchParentSize())
              Icon(
                imageVector = ImageVector.vectorResource(CoreUiR.drawable.symbol_x_24),
                contentDescription = stringResource(R.string.TellomiFeedCompose__remove_image),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                  .align(Alignment.TopEnd)
                  .padding(4.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                  .clickable { onEvidenceRemoved(uri) }
                  .padding(2.dp)
                  .size(16.dp)
              )
            }
          }
          if (room > 0) {
            item(key = "add") {
              Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                  .size(80.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant)
                  .clickable {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (room > 1) pickMany.launch(request) else pickOne.launch(request)
                  }
              ) {
                Text(text = "+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }
      }
    }

    Buttons.LargeTonal(onClick = onSubmit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth().testTag(FeedReportTestTags.SUBMIT)) {
      if (state.sending) {
        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
      } else {
        Text(stringResource(R.string.TellomiFeedReport__submit))
      }
    }

    if (state.showMainlandHotline) {
      Hotline(onOpenHotline = onOpenHotline)
    }
  }
}

/** 大陆区：违法和不良信息举报中心 12377（第 32 条）。 */
@Composable
private fun Hotline(onOpenHotline: () -> Unit, modifier: Modifier = Modifier) {
  Row(modifier = modifier) {
    Text(
      text = stringResource(R.string.TellomiFeedReport__hotline),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.clickable(onClick = onOpenHotline)
    )
  }
}

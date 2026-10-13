/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Dialogs
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Snackbars
import org.signal.core.ui.compose.Texts
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedKeywordRequest
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import org.thoughtcrime.securesms.feed.parseInstantMillis
import org.signal.core.ui.R as CoreUiR

object FeedRecommendationSettingsTestTags {
  const val PERSONALIZED = "feed_rec_personalized"
  const val DELETE = "feed_rec_delete_"
  const val RESET = "feed_rec_reset"
  const val STYLE = "feed_rec_style"
  const val SENSITIVE = "feed_rec_sensitive"
  const val CATEGORIES = "feed_rec_categories"
  const val CATEGORY = "feed_rec_category_"
  const val PAUSE = "feed_rec_pause"
  const val KEYWORD_ADD = "feed_rec_keyword_add"
  const val KEYWORD_FIELD = "feed_rec_keyword_field"
  const val KEYWORD_BLOCK = "feed_rec_keyword_block"
  const val KEYWORD_DAYS = "feed_rec_keyword_days_"
  const val KEYWORD_CONFIRM = "feed_rec_keyword_confirm"
  const val KEYWORD_DELETE = "feed_rec_keyword_delete_"
}

/**
 * 「动态设置」：个性化推荐开关、兴趣标签（只有自己看得到，能删）、重置推荐（确认两次）。
 *
 * 还有第 12 条的最近看的内容分布、推荐风格、敏感内容、按类别调整、屏蔽关键词、暂停推荐 30 天。
 */
@Composable
fun FeedRecommendationSettingsScreen(
  state: FeedRecommendationSettingsState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onPersonalizedChanged: (Boolean) -> Unit,
  onStyleChanged: (String) -> Unit = {},
  onSensitiveChanged: (String) -> Unit = {},
  onCategoryChanged: (String, String) -> Unit = { _, _ -> },
  onPause: () -> Unit = {},
  onResume: () -> Unit = {},
  onAddKeyword: (String, FeedKeyword.Mode, Int) -> Unit = { _, _, _ -> },
  onRemoveKeyword: (FeedKeyword) -> Unit = {},
  onDeleteInterest: (String, FeedInterest) -> Unit,
  onReset: () -> Unit,
  onNoticeShown: () -> Unit
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val noticeText = state.notice?.let {
    stringResource(
      when (it) {
        FeedRecommendationSettingsState.Notice.RESET -> R.string.TellomiFeedRecommendation__reset_done
        FeedRecommendationSettingsState.Notice.INVALID_KEYWORD -> R.string.TellomiFeedKeywords__invalid
        FeedRecommendationSettingsState.Notice.TOO_MANY_KEYWORDS -> R.string.TellomiFeedKeywords__too_many
        FeedRecommendationSettingsState.Notice.FAILED -> R.string.TellomiFeed__action_failed
      }
    )
  }
  LaunchedEffect(state.notice) {
    if (noticeText != null) {
      snackbarHostState.showSnackbar(noticeText)
      onNoticeShown()
    }
  }

  // 0 = 没在确认，1 = 第一次确认，2 = 第二次确认
  var resetStep by rememberSaveable { mutableIntStateOf(0) }
  var confirmPause by rememberSaveable { mutableStateOf(false) }
  var categoriesOpen by rememberSaveable { mutableStateOf(false) }
  var addingKeyword by rememberSaveable { mutableStateOf(false) }

  Scaffolds.Settings(
    title = stringResource(R.string.TellomiFeedRecommendation__title),
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back),
    snackbarHost = { Snackbars.Host(snackbarHostState) }
  ) { padding ->
    val preferences = state.preferences
    val interests = state.interests
    when {
      preferences == null && state.loadFailed -> Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(padding).fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(text = stringResource(R.string.TellomiFeed__load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
          TextButton(onClick = onRetry) { Text(stringResource(R.string.TellomiFeed__retry)) }
        }
      }
      preferences == null || interests == null -> Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(padding).fillMaxSize()) {
        CircularProgressIndicator()
      }
      else -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
        if (state.personalizationToggleVisible) {
          item {
            Rows.ToggleRow(
              checked = preferences.personalized,
              text = stringResource(R.string.TellomiFeedRecommendation__personalized),
              label = stringResource(R.string.TellomiFeedRecommendation__personalized_description),
              onCheckChanged = onPersonalizedChanged,
              modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.PERSONALIZED)
            )
          }
        } else {
          item {
            // 编辑精选阶段（R-1.5）推荐本来就不按个人兴趣，没有开关可给
            Text(
              text = stringResource(R.string.TellomiFeedRecommendation__editorial_note),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(Rows.defaultPadding())
            )
          }
        }

        if (preferences.recentTopics.isNotEmpty()) {
          item { Texts.SectionHeader(stringResource(R.string.TellomiFeedRecommendation__recent)) }
          items(preferences.recentTopics, key = { "recent-${it.key}" }) { topic ->
            RecentTopicRow(label = topicLabel(topic.key), share = topic.share)
          }
        }

        item { Dividers.Default() }
        item { Texts.SectionHeader(stringResource(R.string.TellomiFeedRecommendation__content)) }
        item {
          Rows.RadioListRow(
            text = stringResource(R.string.TellomiFeedRecommendation__style),
            labels = arrayOf(stringResource(R.string.TellomiFeedRecommendation__style_keep), stringResource(R.string.TellomiFeedRecommendation__style_explore)),
            values = FeedPreferences.STYLES.toTypedArray(),
            selectedValue = preferences.style,
            onSelected = onStyleChanged,
            modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.STYLE)
          )
        }
        item {
          val choices = state.sensitiveChoices
          Rows.RadioListRow(
            text = stringResource(R.string.TellomiFeedRecommendation__sensitive),
            labels = choices.map { stringResource(sensitiveLabel(it)) }.toTypedArray(),
            values = choices.toTypedArray(),
            selectedValue = preferences.sensitive,
            onSelected = onSensitiveChanged,
            modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.SENSITIVE)
          )
        }
        item {
          val adjusted = preferences.categories.size
          Rows.TextRow(
            text = stringResource(R.string.TellomiFeedRecommendation__categories),
            label = if (adjusted > 0) pluralStringResource(R.plurals.TellomiFeedRecommendation__categories_adjusted, adjusted, adjusted) else stringResource(R.string.TellomiFeedRecommendation__categories_hint),
            onClick = { categoriesOpen = !categoriesOpen },
            modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.CATEGORIES)
          )
        }
        if (categoriesOpen) {
          items(FeedTopicNames.slugs, key = { "category-$it" }) { slug ->
            CategoryRow(
              label = topicLabel(slug),
              value = preferences.categories[slug] ?: FeedPreferences.CATEGORY_DEFAULT,
              onChange = { onCategoryChanged(slug, it) },
              tag = FeedRecommendationSettingsTestTags.CATEGORY + slug
            )
          }
        }

        item { Dividers.Default() }
        item { Texts.SectionHeader(stringResource(R.string.TellomiFeedRecommendation__interests)) }
        item {
          Text(
            text = stringResource(R.string.TellomiFeedRecommendation__interests_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Rows.defaultPadding())
          )
        }

        if (interests.isEmpty) {
          item {
            Text(
              text = stringResource(R.string.TellomiFeedRecommendation__interests_empty),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(Rows.defaultPadding())
            )
          }
        } else {
          interestGroup(R.string.TellomiFeedRecommendation__topics, FeedInterests.DIM_TOPIC, interests.topics, onDeleteInterest)
          interestGroup(R.string.TellomiFeedRecommendation__authors, FeedInterests.DIM_AUTHOR, interests.authors, onDeleteInterest)
          interestGroup(R.string.TellomiFeedRecommendation__sources, FeedInterests.DIM_SOURCE, interests.sources, onDeleteInterest)
        }

        item { Dividers.Default() }
        item { Texts.SectionHeader(stringResource(R.string.TellomiFeedKeywords__title)) }
        item {
          Text(
            text = stringResource(R.string.TellomiFeedKeywords__description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Rows.defaultPadding())
          )
        }
        items(state.keywords, key = { "keyword-${it.mode}-${it.keyword}" }) { keyword ->
          KeywordRow(keyword = keyword, onRemove = { onRemoveKeyword(keyword) })
        }
        item {
          Rows.TextRow(
            text = stringResource(R.string.TellomiFeedKeywords__add),
            icon = SignalIcons.Plus.imageVector,
            onClick = { addingKeyword = true },
            modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.KEYWORD_ADD)
          )
        }

        item { Dividers.Default() }
        item {
          val pausedUntil = preferences.pausedUntil?.let { parseInstantMillis(it) }
          if (pausedUntil != null) {
            Rows.TextRow(
              text = stringResource(R.string.TellomiFeedRecommendation__paused, DateUtils.formatDateTime(LocalContext.current, pausedUntil, DateUtils.FORMAT_SHOW_DATE)),
              label = stringResource(R.string.TellomiFeedRecommendation__resume),
              onClick = onResume,
              modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.PAUSE)
            )
          } else {
            Rows.TextRow(
              text = stringResource(R.string.TellomiFeedRecommendation__pause),
              label = stringResource(R.string.TellomiFeedRecommendation__pause_description),
              onClick = { confirmPause = true },
              modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.PAUSE)
            )
          }
        }
        item {
          Rows.TextRow(
            text = {
              Column {
                Text(
                  text = stringResource(R.string.TellomiFeedRecommendation__reset),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.error
                )
                Text(
                  text = stringResource(R.string.TellomiFeedRecommendation__reset_description),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            },
            enabled = !state.resetting,
            onClick = { resetStep = 1 },
            modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.RESET)
          )
        }
      }
    }
  }

  if (addingKeyword) {
    AddKeywordDialog(
      onAdd = { keyword, mode, days ->
        addingKeyword = false
        onAddKeyword(keyword, mode, days)
      },
      onDismiss = { addingKeyword = false }
    )
  }

  if (confirmPause) {
    Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedRecommendation__pause_title),
      body = stringResource(R.string.TellomiFeedRecommendation__pause_body),
      confirm = stringResource(R.string.TellomiFeedRecommendation__pause_confirm),
      dismiss = stringResource(android.R.string.cancel),
      onConfirm = {
        confirmPause = false
        onPause()
      },
      onDismiss = { confirmPause = false }
    )
  }

  when (resetStep) {
    1 -> Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedRecommendation__reset_title),
      body = stringResource(R.string.TellomiFeedRecommendation__reset_body),
      confirm = stringResource(R.string.TellomiFeedRecommendation__continue),
      dismiss = stringResource(android.R.string.cancel),
      onConfirm = { resetStep = 2 },
      onDismiss = { if (resetStep == 1) resetStep = 0 }
    )
    2 -> Dialogs.SimpleAlertDialog(
      title = stringResource(R.string.TellomiFeedRecommendation__reset_confirm_title),
      body = stringResource(R.string.TellomiFeedRecommendation__reset_confirm_body),
      confirm = stringResource(R.string.TellomiFeedRecommendation__reset),
      dismiss = stringResource(android.R.string.cancel),
      onConfirm = {
        resetStep = 0
        onReset()
      },
      onDismiss = { resetStep = 0 },
      confirmColor = MaterialTheme.colorScheme.error
    )
  }
}

private fun LazyListScope.interestGroup(
  title: Int,
  dimension: String,
  interests: List<FeedInterest>,
  onDelete: (String, FeedInterest) -> Unit
) {
  if (interests.isEmpty()) return
  item(key = "header-$dimension") {
    Text(
      text = stringResource(title),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)
    )
  }
  items(interests, key = { "$dimension-${it.key}" }) { interest ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)
    ) {
      Text(
        text = interestLabel(dimension, interest),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f)
      )
      IconButton(
        onClick = { onDelete(dimension, interest) },
        modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.DELETE + "$dimension-${interest.key}")
      ) {
        Icon(imageVector = SignalIcons.X.imageVector, contentDescription = stringResource(R.string.TellomiFeedRecommendation__delete))
      }
    }
  }
}

@Composable
private fun interestLabel(dimension: String, interest: FeedInterest): String {
  if (dimension == FeedInterests.DIM_TOPIC) return topicLabel(interest.key)
  return interest.label?.takeIf { it.isNotBlank() } ?: interest.key
}

@Composable
private fun topicLabel(slug: String): String = FeedTopicNames.of(slug)?.let { stringResource(it) } ?: "#$slug"

private fun sensitiveLabel(value: String): Int = when (value) {
  FeedPreferences.SENSITIVE_REDUCE -> R.string.TellomiFeedRecommendation__sensitive_reduce
  FeedPreferences.SENSITIVE_MORE -> R.string.TellomiFeedRecommendation__sensitive_more
  else -> R.string.TellomiFeedRecommendation__sensitive_standard
}

/** 最近 30 天看过的内容里这个话题占多少：名字 + 比例条 + 百分比。 */
@Composable
private fun RecentTopicRow(label: String, share: Double) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)
  ) {
    Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(140.dp))
    LinearProgressIndicator(
      progress = { share.toFloat().coerceIn(0f, 1f) },
      drawStopIndicator = {},
      modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
    )
    Text(
      text = "${Math.round(share * 100)}%",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

/** 一个类别：减少 / 默认 / 增加。 */
@Composable
private fun CategoryRow(label: String, value: String, onChange: (String) -> Unit, tag: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp).testTag(tag)
  ) {
    Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FeedPreferences.CATEGORY_VALUES.forEach { choice ->
        FilterChip(
          selected = choice == value,
          onClick = { onChange(choice) },
          label = {
            Text(
              stringResource(
                when (choice) {
                  FeedPreferences.CATEGORY_REDUCE -> R.string.TellomiFeedRecommendation__category_reduce
                  FeedPreferences.CATEGORY_INCREASE -> R.string.TellomiFeedRecommendation__category_increase
                  else -> R.string.TellomiFeedRecommendation__category_default
                }
              )
            )
          }
        )
      }
    }
  }
}

/** 一个屏蔽关键词：词 + 「少推荐 / 完全屏蔽 · 永久 / 到哪天」+ 删除。 */
@Composable
private fun KeywordRow(keyword: FeedKeyword, onRemove: () -> Unit) {
  val mode = stringResource(if (keyword.mode == FeedKeyword.Mode.BLOCK) R.string.TellomiFeedKeywords__block else R.string.TellomiFeedKeywords__downrank)
  val until = keyword.expiresAt?.let { stringResource(R.string.TellomiFeedKeywords__until, DateUtils.formatDateTime(LocalContext.current, it, DateUtils.FORMAT_SHOW_DATE)) }
    ?: stringResource(R.string.TellomiFeedKeywords__forever)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = keyword.keyword, style = MaterialTheme.typography.bodyLarge)
      Text(text = "$mode · $until", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    IconButton(
      onClick = onRemove,
      modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.KEYWORD_DELETE + keyword.keyword)
    ) {
      Icon(imageVector = SignalIcons.X.imageVector, contentDescription = stringResource(R.string.TellomiFeedRecommendation__delete))
    }
  }
}

/** 加关键词：词（最多 20 个字）、少推荐（默认）/ 完全屏蔽、永久 / 30 天 / 7 天。 */
@Composable
private fun AddKeywordDialog(onAdd: (String, FeedKeyword.Mode, Int) -> Unit, onDismiss: () -> Unit) {
  var form by rememberSaveable(stateSaver = KeywordFormSaver) { mutableStateOf(KeywordForm()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.TellomiFeedKeywords__add)) },
    text = { AddKeywordForm(form = form, onChange = { form = it }) },
    confirmButton = {
      TextButton(
        enabled = form.valid,
        onClick = { onAdd(form.trimmed, if (form.block) FeedKeyword.Mode.BLOCK else FeedKeyword.Mode.DOWNRANK, form.days) },
        modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.KEYWORD_CONFIRM)
      ) {
        Text(stringResource(R.string.TellomiFeedKeywords__confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
    }
  )
}

/** 加关键词对话框里填的东西。 */
data class KeywordForm(val text: String = "", val block: Boolean = false, val days: Int = 0) {
  val trimmed: String get() = text.trim()
  val valid: Boolean get() = trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= FeedKeywordRequest.MAX_CHARS
}

private val KeywordFormSaver = listSaver<KeywordForm, Any>(
  save = { listOf(it.text, it.block, it.days) },
  restore = { KeywordForm(it[0] as String, it[1] as Boolean, it[2] as Int) }
)

@Composable
internal fun AddKeywordForm(form: KeywordForm, onChange: (KeywordForm) -> Unit) {
  Column {
    OutlinedTextField(
      value = form.text,
      onValueChange = { onChange(form.copy(text = it)) },
      singleLine = true,
      isError = form.trimmed.isNotEmpty() && !form.valid,
      placeholder = { Text(stringResource(R.string.TellomiFeedKeywords__hint)) },
      modifier = Modifier.fillMaxWidth().testTag(FeedRecommendationSettingsTestTags.KEYWORD_FIELD)
    )
    ModeChoice(selected = !form.block, title = R.string.TellomiFeedKeywords__downrank, body = R.string.TellomiFeedKeywords__downrank_description, onClick = { onChange(form.copy(block = false)) }, tag = null)
    ModeChoice(selected = form.block, title = R.string.TellomiFeedKeywords__block, body = R.string.TellomiFeedKeywords__block_description, onClick = { onChange(form.copy(block = true)) }, tag = FeedRecommendationSettingsTestTags.KEYWORD_BLOCK)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
      FeedKeywordRequest.DAYS.forEach { choice ->
        FilterChip(
          selected = form.days == choice,
          onClick = { onChange(form.copy(days = choice)) },
          label = { Text(if (choice == 0) stringResource(R.string.TellomiFeedKeywords__forever) else pluralStringResource(R.plurals.TellomiFeedKeywords__days, choice, choice)) },
          modifier = Modifier.testTag(FeedRecommendationSettingsTestTags.KEYWORD_DAYS + choice)
        )
      }
    }
  }
}

@Composable
private fun ModeChoice(selected: Boolean, title: Int, body: Int, onClick: () -> Unit, tag: String?) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 8.dp)
      .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
      .then(if (tag != null) Modifier.testTag(tag) else Modifier)
  ) {
    RadioButton(selected = selected, onClick = null)
    Column(modifier = Modifier.padding(start = 8.dp)) {
      Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
      Text(text = stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

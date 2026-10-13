/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.thoughtcrime.securesms.feed.settings.FeedTopicNames

/** 话题的显示名：认识的类别用本地文案，不认识的直接显示 slug。 */
@Composable
fun feedTopicLabel(slug: String): String = FeedTopicNames.of(slug)?.let { stringResource(it) } ?: slug

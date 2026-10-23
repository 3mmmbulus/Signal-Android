/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.signal.core.ui.R as CoreUiR

private val LOTTIE_SIZE = 28.dp

/**
 * Chats list bottom navigation bar.
 */
@Composable
fun MainNavigationBar(
  state: MainNavigationBarState,
  onDestinationSelected: (MainListRoute) -> Unit
) {
  NavigationBar(
    containerColor = SignalTheme.colors.colorSurface2,
    contentColor = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.height(if (state.compact) 48.dp else 80.dp),
    windowInsets = WindowInsets(0, 0, 0, 0)
  ) {
    state.destinations.forEach { destination ->
      val badgeCount = when (destination) {
        MainListRoute.Feed -> 0
        MainListRoute.Archive -> error("Not supported")
        MainListRoute.Chats -> state.chatsCount
        MainListRoute.Calls -> state.callsCount
        MainListRoute.Stories -> state.storiesCount
        MainListRoute.Friends, MainListRoute.Me -> 0
      }

      val selected = state.currentListLocation == destination
      NavigationBarItem(
        selected = selected,
        icon = {
          NavigationDestinationIcon(
            destination = destination,
            selected = selected
          )
        },
        label = if (state.compact) null else {
          { NavigationDestinationLabel(destination) }
        },
        onClick = {
          onDestinationSelected(destination)
        },
        modifier = Modifier.drawNavigationBarBadge(count = badgeCount, compact = state.compact)
      )
    }
  }
}

/**
 * Draws badge over navigation bar item. We do this since they're required to be inside a row,
 * and things get really funky or clip weird if we try to use a normal composable.
 */
@Composable
private fun Modifier.drawNavigationBarBadge(count: Int, compact: Boolean): Modifier {
  return if (count <= 0) {
    this
  } else {
    val formatted = formatCount(count)
    val textMeasurer = rememberTextMeasurer()
    val color = colorResource(R.color.ConversationListTabs__unread)
    val textStyle = MaterialTheme.typography.labelMedium
    val textLayoutResult = remember(formatted) {
      textMeasurer.measure(formatted, textStyle)
    }

    var size by remember { mutableStateOf(IntSize.Zero) }

    val padding = with(LocalDensity.current) {
      4.dp.toPx()
    }

    val xOffsetExtra = with(LocalDensity.current) {
      4.dp.toPx()
    }

    val yOffset = with(LocalDensity.current) {
      if (compact) 6.dp.toPx() else 10.dp.toPx()
    }

    this
      .onSizeChanged {
        size = it
      }
      .drawWithContent {
        drawContent()

        val xOffset = size.width.toFloat() / 2f + xOffsetExtra
        val yRadius = size.height.toFloat() / 2f

        if (size != IntSize.Zero) {
          drawRoundRect(
            color = color,
            topLeft = Offset(xOffset, yOffset),
            size = Size(textLayoutResult.size.width.toFloat() + padding * 2, textLayoutResult.size.height.toFloat()),
            cornerRadius = CornerRadius(yRadius, yRadius)
          )

          drawText(
            textLayoutResult = textLayoutResult,
            color = Color.White,
            topLeft = Offset(xOffset + padding, yOffset)
          )
        }
      }
  }
}

/**
 * Navigation Rail for medium and large form factor devices.
 */
@Composable
fun MainNavigationRail(
  state: MainNavigationBarState,
  mainFloatingActionButtonsCallback: MainFloatingActionButtonsCallback,
  onDestinationSelected: (MainListRoute) -> Unit
) {
  NavigationRail(
    containerColor = SignalTheme.colors.colorSurface1
  ) {
    Spacer(modifier = Modifier.height(40.dp).weight(1f, fill = false))

    MainFloatingActionButtons(
      destination = state.currentListLocation,
      callback = mainFloatingActionButtonsCallback,
      modifier = Modifier.padding(vertical = 8.dp)
    )

    Spacer(modifier = Modifier.height(40.dp).weight(1f, fill = false))

    val selectedDestination = if (state.currentListLocation == MainListRoute.Archive) {
      MainListRoute.Chats
    } else {
      state.currentListLocation
    }

    state.destinations.forEachIndexed { idx, destination ->
      val selected = selectedDestination == destination

      Box {
        NavigationRailItem(
          modifier = Modifier.padding(bottom = if (state.destinations.lastIndex == idx) 0.dp else 16.dp),
          icon = {
            NavigationDestinationIcon(
              destination = destination,
              selected = selected
            )
          },
          label = {
            NavigationDestinationLabel(destination)
          },
          selected = selected,
          onClick = {
            onDestinationSelected(destination)
          }
        )

        NavigationRailCountIndicator(
          state = state,
          destination = destination
        )
      }
    }
  }
}

@Composable
private fun BoxScope.NavigationRailCountIndicator(
  state: MainNavigationBarState,
  destination: MainListRoute
) {
  val count = remember(state, destination) {
    when (destination) {
      MainListRoute.Feed -> 0
      MainListRoute.Archive -> error("Not supported")
      MainListRoute.Chats -> state.chatsCount
      MainListRoute.Calls -> state.callsCount
      MainListRoute.Stories -> state.storiesCount
      MainListRoute.Friends, MainListRoute.Me -> 0
    }
  }

  if (count > 0) {
    Box(
      modifier = Modifier
        .padding(start = 42.dp)
        .height(16.dp)
        .defaultMinSize(minWidth = 16.dp)
        .background(color = colorResource(R.color.ConversationListTabs__unread), shape = RoundedCornerShape(percent = 50))
        .align(Alignment.TopStart)
    ) {
      Text(
        text = formatCount(count),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
          .align(Alignment.Center)
          .padding(horizontal = 4.dp)
      )
    }
  }
}

@Composable
private fun NavigationDestinationIcon(
  destination: MainListRoute,
  selected: Boolean
) {
  if (destination == MainListRoute.Feed) {
    TellomiFeedDestinationIcon(selected = selected)
    return
  }

  if (destination == MainListRoute.Friends || destination == MainListRoute.Me) {
    TellomiStaticDestinationIcon(destination = destination, selected = selected)
    return
  }

  val dynamicProperties = rememberLottieDynamicProperties(
    rememberLottieDynamicProperty(
      property = LottieProperty.COLOR_FILTER,
      value = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
        MaterialTheme.colorScheme.onSurface.hashCode(),
        BlendModeCompat.SRC_ATOP
      ),
      keyPath = arrayOf("**")
    )
  )

  val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(destination.icon))
  val progress by animateFloatAsState(targetValue = if (selected) 1f else 0f, animationSpec = tween(durationMillis = composition?.duration?.toInt() ?: 0))

  LottieAnimation(
    composition = composition,
    // Tellomi（交互审计 A-23）：取消选中时图标倒着播回去，不再一下跳回第 0 帧。
    progress = { progress },
    dynamicProperties = dynamicProperties,
    modifier = Modifier.size(LOTTIE_SIZE)
  )
}

/**
 * Tellomi（M11 动态）：「动态」的底栏图标。选中时换成实心，颜色和其他三个 Lottie 图标一样跟着 onSurface 走。
 * 交互与动效审计里「Tab 图标选中时轻弹一次」那条随底栏改版一起做（`10-feed-prototype-log.md` 第 417 行）。
 */
@Composable
private fun TellomiFeedDestinationIcon(selected: Boolean) {
  Icon(
    imageVector = ImageVector.vectorResource(if (selected) R.drawable.tellomi_feed_fill_28 else R.drawable.tellomi_feed_28),
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.size(LOTTIE_SIZE)
  )
}

/**
 * Tellomi（tellomi/tellomi#1355）：「好友」「我」的底栏图标（iOS 用 SF Symbols `person.2` / `person.crop.circle`，
 * 这里用 Signal 自带的同类图标）。没有实心版，选中状态靠底栏自己的指示块表示。
 */
@Composable
private fun TellomiStaticDestinationIcon(destination: MainListRoute, selected: Boolean) {
  val icon = if (destination == MainListRoute.Friends) R.drawable.symbol_group_24 else CoreUiR.drawable.symbol_person_circle_24
  Icon(
    imageVector = ImageVector.vectorResource(icon),
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.size(LOTTIE_SIZE)
  )
}

@Composable
private fun NavigationDestinationLabel(destination: MainListRoute) {
  Text(stringResource(destination.label))
}

@Composable
private fun formatCount(count: Int): String {
  if (count > 99) {
    return stringResource(R.string.ConversationListTabs__99p)
  }
  return count.toString()
}

@DayNightPreviews
@Preview(device = "spec:parent=pixel_7,orientation=landscape")
@Composable
private fun MainNavigationRailPreview() {
  Previews.Preview {
    var selected by remember { mutableStateOf(MainListRoute.Chats) }

    MainNavigationRail(
      state = MainNavigationBarState(
        chatsCount = 500,
        callsCount = 10,
        storiesCount = 5,
        currentListLocation = selected
      ),
      mainFloatingActionButtonsCallback = MainFloatingActionButtonsCallback.Empty,
      onDestinationSelected = { selected = it }
    )
  }
}

@DayNightPreviews
@Composable
private fun MainNavigationBarPreview() {
  Previews.Preview {
    var selected by remember { mutableStateOf(MainListRoute.Chats) }

    MainNavigationBar(
      state = MainNavigationBarState(
        chatsCount = 500,
        callsCount = 10,
        storiesCount = 5,
        currentListLocation = selected,
        compact = false
      ),
      onDestinationSelected = { selected = it }
    )
  }
}

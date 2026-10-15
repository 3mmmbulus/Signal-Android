/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Colours [ChatScreen] paints behind the conversation. Pushed in from the fragment, since none of them
 * are derivable from anything Compose can see.
 */
@Stable
class ChatScrimState {

  /** Matches the toolbar scrim, so the two read as one band. */
  @get:ColorInt
  var statusBarColor: Int by mutableIntStateOf(Color.TRANSPARENT)

  @get:ColorInt
  var navigationBarColor: Int by mutableIntStateOf(Color.TRANSPARENT)
}

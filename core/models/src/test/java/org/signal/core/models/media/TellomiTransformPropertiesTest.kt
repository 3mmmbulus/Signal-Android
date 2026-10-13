/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.models.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0068 (tellomi/tellomi#1263): `tellomiAsFile` rides in the attachment's transform_properties JSON. */
class TellomiTransformPropertiesTest {

  private val jackson = ObjectMapper().registerKotlinModule()

  @Test
  fun `only written when true, so other attachments keep upstream's JSON`() {
    assertFalse(Json.encodeToString(TransformProperties.empty()).contains("tellomiAsFile"))
    assertFalse(jackson.writeValueAsString(TransformProperties.empty()).contains("tellomiAsFile"))
  }

  @Test
  fun `as file skips the transform and is written as the literal the media queries look for`() {
    val asFile = TransformProperties.forTellomiAsFile()
    assertTrue(asFile.skipTransform)
    assertTrue(asFile.tellomiAsFile)

    // MediaTable matches `transform_properties LIKE '%"tellomiAsFile":true%'`
    assertTrue(Json.encodeToString(asFile).contains("\"tellomiAsFile\":true"))
    assertTrue(jackson.writeValueAsString(asFile).contains("\"tellomiAsFile\":true"))
  }

  @Test
  fun `round trips through both parsers, and is kept by copy`() {
    val json = Json.encodeToString(TransformProperties.forTellomiAsFile())
    assertTrue(Json.decodeFromString<TransformProperties>(json).tellomiAsFile)
    assertTrue(jackson.readValue(json, TransformProperties::class.java).tellomiAsFile)

    assertTrue(TransformProperties.forTellomiAsFile().withSkipTransform().withMp4FastStart().tellomiAsFile)
  }

  @Test
  fun `json written before this field reads as not as file`() {
    val legacy = "{\"skipTransform\":true,\"videoTrim\":false,\"videoTrimStartTimeUs\":0,\"videoTrimEndTimeUs\":0,\"sentMediaQuality\":0,\"mp4Faststart\":false,\"videoMuted\":false,\"videoEdited\":false}"
    assertFalse(Json.decodeFromString<TransformProperties>(legacy).tellomiAsFile)
    assertFalse(jackson.readValue(legacy, TransformProperties::class.java).tellomiAsFile)
    assertEquals(true, jackson.readValue(legacy, TransformProperties::class.java).skipTransform)
  }
}

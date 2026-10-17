/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec attachment-files-location L-11: location messages from Signal-iOS, Signal-Android and Tellomi are recognised; other text is not. Same cases as iOS. */
class TellomiLocationMessageTest {

  // Signal-iOS (upstream `Location.messageText`: address lines + a blank line + the link)

  @Test
  fun signalIosWithAMultiLineAddress() {
    val location = TellomiLocationMessage.parse("1 Infinite Loop\nCupertino CA 95014\nUnited States\n\nhttps://maps.google.com/maps?q=37.33233141%2C-122.0312186")!!
    assertEquals(37.33233141, location.latitude, 0.0)
    assertEquals(-122.0312186, location.longitude, 0.0)
    assertNull(location.name)
    assertEquals("1 Infinite Loop, Cupertino CA 95014, United States", location.address)
    assertFalse(location.isPlace)
  }

  @Test
  fun signalIosWithAChineseAddressJoinsWithoutCommas() {
    val location = TellomiLocationMessage.parse("中国\n上海市静安区\n南京西路789号\n\nhttps://maps.google.com/maps?q=31.2304%2C121.4737")!!
    assertNull(location.name)
    assertEquals("中国上海市静安区南京西路789号", location.address)
  }

  @Test
  fun signalIosWithoutAnAddressIsJustTheLink() {
    val location = TellomiLocationMessage.parse("https://maps.google.com/maps?q=-33.8688%2C151.2093")!!
    assertEquals(-33.8688, location.latitude, 0.0)
    assertEquals(151.2093, location.longitude, 0.0)
    assertNull(location.name)
    assertNull(location.address)
  }

  /** Swift writes small numbers in scientific notation ("\(1.0e-05)" = "1e-05"). */
  @Test
  fun signalIosCoordinatesInScientificNotation() {
    val location = TellomiLocationMessage.parse("https://maps.google.com/maps?q=1e-05%2C-2.5e-06")!!
    assertEquals(0.00001, location.latitude, 0.0)
    assertEquals(-0.0000025, location.longitude, 0.0)
  }

  // Signal-Android (upstream `SignalPlace.getDescription()`: [name\n][address\n]link, no blank line)

  @Test
  fun signalAndroidWithAnAddress() {
    val location = TellomiLocationMessage.parse("1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA\nhttps://maps.google.com/maps?q=37.4219983%2C-122.084")!!
    assertEquals(37.4219983, location.latitude, 0.0)
    assertEquals(-122.084, location.longitude, 0.0)
    assertNull(location.name)
    assertEquals("1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA", location.address)
  }

  /** Older Signal-Android versions sent the name of a picked place. */
  @Test
  fun signalAndroidWithANameAndAnAddress() {
    val location = TellomiLocationMessage.parse("Googleplex\n1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA\nhttps://maps.google.com/maps?q=37.4219983%2C-122.084")!!
    assertEquals("Googleplex", location.name)
    assertEquals("1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA", location.address)
    assertTrue(location.isPlace)
  }

  // Tellomi (a picked place: name\none-line address\nlink)

  @Test
  fun tellomiPlace() {
    val location = TellomiLocationMessage.parse("星巴克臻选上海烘焙工坊\n静安区南京西路789号兴业太古汇\nhttps://maps.google.com/maps?q=31.2297%2C121.4560")!!
    assertEquals("星巴克臻选上海烘焙工坊", location.name)
    assertEquals("静安区南京西路789号兴业太古汇", location.address)
    assertEquals(31.2297, location.latitude, 0.0)
    assertEquals(121.4560, location.longitude, 0.0)
    assertTrue(location.isPlace)
  }

  @Test
  fun oneLineAboveTheLinkIsAnAddress() {
    val location = TellomiLocationMessage.parse("外滩\nhttps://maps.google.com/maps?q=31.2400%2C121.4900")!!
    assertNull(location.name)
    assertEquals("外滩", location.address)
  }

  // Tolerated variations

  @Test
  fun unencodedCommaHttpExtraParametersAndSpaces() {
    listOf(
      "https://maps.google.com/maps?q=37.1,-122.2",
      "http://maps.google.com/maps?q=37.1%2C-122.2",
      "https://MAPS.GOOGLE.COM/maps?q=37.1%2C-122.2&z=15",
      "https://maps.google.com/?q=37.1%2C-122.2",
      "https://maps.google.com/maps?q=37.1%2C%20-122.2",
      "Somewhere\r\n\r\nhttps://maps.google.com/maps?q=37.1%2C-122.2\r\n",
      "  Somewhere  \n\n  https://maps.google.com/maps?q=37.1%2C-122.2  \n\n",
      "https://maps.google.com/maps?q=90%2C180",
      "https://maps.google.com/maps?q=-90%2C-180"
    ).forEach { assertNotNull(it, TellomiLocationMessage.parse(it)) }
  }

  @Test
  fun blankLinesAboveTheTextAreIgnored() {
    val location = TellomiLocationMessage.parse("\n\nName\nAddress\nhttps://maps.google.com/maps?q=1%2C2")!!
    assertEquals("Name", location.name)
    assertEquals("Address", location.address)
  }

  // Not a location

  @Test
  fun malformedOrOtherTextIsNotALocation() {
    listOf(
      null,
      "",
      "\n\n",
      "Hello",
      "https://maps.google.com/maps",
      "https://maps.google.com/maps?q=",
      "https://maps.google.com/maps?q=abc%2Cdef",
      "https://maps.google.com/maps?q=37.1",
      "https://maps.google.com/maps?q=37.1%2C-122.2%2C5",
      "https://maps.google.com/maps?q=91%2C0",
      "https://maps.google.com/maps?q=0%2C181",
      "https://maps.google.com/maps?q=NaN%2C0",
      "https://maps.google.com/maps?q=Infinity%2C0",
      "https://maps.google.com/maps?ll=37.1%2C-122.2",
      "https://maps.google.com/search?q=37.1%2C-122.2",
      "https://maps.google.com.evil.example/maps?q=37.1%2C-122.2",
      "https://example.com/maps?q=37.1%2C-122.2",
      "ftp://maps.google.com/maps?q=37.1%2C-122.2",
      "maps.google.com/maps?q=37.1%2C-122.2",
      "Meet here https://maps.google.com/maps?q=37.1%2C-122.2",
      "https://maps.google.com/maps?q=37.1%2C-122.2 see you",
      "https://maps.google.com/maps?q=37.1%2C-122.2\nsee you there",
      "1\n2\n3\n4\n5\n6\n7\nhttps://maps.google.com/maps?q=37.1%2C-122.2",
      "x".repeat(301) + "\nhttps://maps.google.com/maps?q=37.1%2C-122.2"
    ).forEach { assertNull(it, TellomiLocationMessage.parse(it)) }
  }

  @Test
  fun sixLinesAboveTheLinkIsStillALocation() {
    val location = TellomiLocationMessage.parse("1\n2\n3\n4\n5\n6\n\nhttps://maps.google.com/maps?q=37.1%2C-122.2")!!
    assertEquals("1, 2, 3, 4, 5, 6", location.address)
  }
}

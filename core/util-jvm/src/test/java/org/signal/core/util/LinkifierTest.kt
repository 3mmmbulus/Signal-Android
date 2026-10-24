/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.util

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Each row supplies an input string and the exact list of [Expected] detections in left-to-right
 * order. An empty list means we expect no detections (i.e. a false-positive guard).
 */
@RunWith(Parameterized::class)
class LinkifierTest(private val case: Case) {

  data class Case(
    val name: String,
    val input: String,
    val expected: List<Expected>
  ) {
    override fun toString(): String = name
  }

  /**
   * @property spanText  The substring of [Case.input] that should be marked as a link.
   * @property url       The normalized URL the [Linkifier.DetectedLink] should expose. Defaults to
   *                     [spanText] for cases where the input already includes a scheme.
   */
  data class Expected(
    val spanText: String,
    val url: String = spanText
  )

  @Test
  fun findLinks() {
    val actual: List<Linkifier.DetectedLink> = Linkifier.findLinks(case.input)

    assertThat(actual).hasSize(case.expected.size)

    // Walk forward through the input as we go, so that repeated spans are matched in order.
    var cursor = 0
    for ((i, expected) in case.expected.withIndex()) {
      val expectedStart = case.input.indexOf(expected.spanText, cursor)
      if (expectedStart < 0) {
        throw AssertionError("expected span <${expected.spanText}> not found in <${case.input}> at or after index $cursor")
      }
      val expectedEnd = expectedStart + expected.spanText.length

      val link = actual[i]
      assertThat(link.start).isEqualTo(expectedStart)
      assertThat(link.end).isEqualTo(expectedEnd)
      assertThat(link.url).isEqualTo(expected.url)

      cursor = expectedEnd
    }
  }

  companion object {

    private fun web(spanText: String, url: String = spanText) = Expected(spanText, url)

    @Parameterized.Parameters(name = "{0}")
    @JvmStatic
    fun cases(): Collection<Array<Any>> = listOf(

      // ----- empty / no-match -----
      Case("empty text", "", emptyList()),
      Case("plain text with no urls", "just some words, nothing fancy.", emptyList()),

      // ----- basic web URLs -----
      Case("https url", "https://signal.org", listOf(web("https://signal.org"))),
      Case("http url", "http://signal.org", listOf(web("http://signal.org"))),
      Case("bare domain gets http prefix", "signal.org", listOf(web("signal.org", url = "http://signal.org"))),
      Case("www-prefixed url gets http prefix", "www.signal.org", listOf(web("www.signal.org", url = "http://www.signal.org"))),
      Case("url with path query and fragment", "https://example.com/foo/bar?x=1&y=2#frag", listOf(web("https://example.com/foo/bar?x=1&y=2#frag"))),
      Case("url with port", "http://localhost:8080/path", listOf(web("http://localhost:8080/path"))),
      Case("unicode IDN domain", "https://кц.рф/path", listOf(web("https://кц.рф/path"))),
      Case("url embedded in sentence", "Check out https://signal.org for details", listOf(web("https://signal.org"))),

      // ----- the trailing-hyphen bug fix -----
      Case("trailing hyphen before space is preserved", "see https://example.com/foo- and bar", listOf(web("https://example.com/foo-"))),
      Case("trailing hyphen at end of string is preserved", "https://example.com/foo-", listOf(web("https://example.com/foo-"))),
      Case("multiple trailing hyphens are preserved", "https://example.com/foo--- end", listOf(web("https://example.com/foo---"))),
      Case("bare domain followed by trailing hyphen excludes the hyphen", "see signal.com- today", listOf(web("signal.com", url = "http://signal.com"))),
      Case("hyphen in middle of url is preserved", "https://example-site.com/a-b-c", listOf(web("https://example-site.com/a-b-c"))),
      Case("url ending in dash followed by another url", "https://example.com/foo- https://other.com", listOf(web("https://example.com/foo-"), web("https://other.com"))),

      // ----- trailing punctuation trimming -----
      Case("trailing period is trimmed", "Visit https://signal.org.", listOf(web("https://signal.org"))),
      Case("trailing comma is trimmed", "https://signal.org, then go", listOf(web("https://signal.org"))),
      Case("trailing exclamation is trimmed", "https://signal.org!", listOf(web("https://signal.org"))),
      Case("trailing question mark is trimmed", "Have you tried https://signal.org?", listOf(web("https://signal.org"))),
      Case("trailing semicolon is trimmed", "Go to https://signal.org; thanks", listOf(web("https://signal.org"))),
      Case("trailing colon is trimmed", "https://signal.org: nice", listOf(web("https://signal.org"))),
      Case("multiple trailing punctuation chars are all trimmed", "Wow https://signal.org!!! amazing", listOf(web("https://signal.org"))),
      Case("trailing slash is preserved", "https://signal.org/ end", listOf(web("https://signal.org/"))),
      Case("trailing underscore is preserved", "https://example.com/path_ tail", listOf(web("https://example.com/path_"))),
      Case("comma in url path is preserved", "Go to https://example.com/a,b/c", listOf(web("https://example.com/a,b/c"))),
      Case("comma in url query is preserved", "Go to https://example.com/search?q=a,b", listOf(web("https://example.com/search?q=a,b"))),
      Case("semicolon in url path is preserved", "https://example.com/pricewatch/compare/2290176;2126182/", listOf(web("https://example.com/pricewatch/compare/2290176;2126182/"))),
      Case("semicolon in url query is preserved", "Go to https://example.com/search?a=1;b=2", listOf(web("https://example.com/search?a=1;b=2"))),

      // ----- apostrophes -----
      Case("apostrophe in url path is preserved", "https://en.wikipedia.org/wiki/Occam's_razor", listOf(web("https://en.wikipedia.org/wiki/Occam's_razor"))),
      Case("apostrophe in url path is preserved mid-sentence", "see https://en.wikipedia.org/wiki/Occam's_razor for more", listOf(web("https://en.wikipedia.org/wiki/Occam's_razor"))),
      Case("apostrophe in bare domain path is preserved", "en.wikipedia.org/wiki/Occam's_razor", listOf(web("en.wikipedia.org/wiki/Occam's_razor", url = "http://en.wikipedia.org/wiki/Occam's_razor"))),
      Case("apostrophe in url query is preserved", "https://example.com/search?q=Occam's", listOf(web("https://example.com/search?q=Occam's"))),
      Case("trailing apostrophe is trimmed", "https://example.com/foo' end", listOf(web("https://example.com/foo"))),
      Case("single-quoted url does not include the quotes", "'https://signal.org'", listOf(web("https://signal.org"))),
      Case("possessive after schemed host is not part of the url", "https://signal.org's blog is good", listOf(web("https://signal.org"))),
      Case("possessive after bare domain is not part of the url", "signal.org's blog is good", listOf(web("signal.org", url = "http://signal.org"))),
      Case("possessive after www domain is not part of the url", "www.signal.org's blog is good", listOf(web("www.signal.org", url = "http://www.signal.org"))),

      // ----- bracket / paren handling -----
      Case("trailing closing paren without opener is trimmed", "(see https://signal.org)", listOf(web("https://signal.org"))),
      Case("trailing closing paren with matching opener inside is preserved", "https://en.wikipedia.org/wiki/Foo_(bar) and more", listOf(web("https://en.wikipedia.org/wiki/Foo_(bar)"))),
      Case("trailing closing bracket without opener is trimmed", "[https://signal.org]", listOf(web("https://signal.org"))),
      Case("trailing closing brace without opener is trimmed", "{https://signal.org}", listOf(web("https://signal.org"))),
      Case("parenthesized url with trailing punctuation has both trimmed", "(see https://signal.org).", listOf(web("https://signal.org"))),

      // ----- multiple URLs in one input -----
      Case("two urls separated by text", "First https://a.com then https://b.com", listOf(web("https://a.com"), web("https://b.com"))),
      Case("two urls separated only by comma", "https://a.com,https://b.com", listOf(web("https://a.com"), web("https://b.com"))),
      Case("two urls separated only by semicolon", "https://a.com;https://b.com", listOf(web("https://a.com"), web("https://b.com"))),
      Case(
        name = "multi-line text with several urls per line",
        input = """
          Line one https://a.com
          Line two https://b.com and https://c.com
          Line three: nothing
        """.trimIndent(),
        expected = listOf(web("https://a.com"), web("https://b.com"), web("https://c.com"))
      ),
      Case(
        name = "github compare url with triple-dot path is captured fully",
        input = "diff: https://github.com/signalapp/Signal-Android/compare/v6.23.2...v6.23.3",
        expected = listOf(web("https://github.com/signalapp/Signal-Android/compare/v6.23.2...v6.23.3"))
      ),

      // ----- boundary handling -----
      Case("explicit-scheme url glued to a leading word is rejected", "foohttps://signal.org", emptyList()),
      // Tellomi: Chinese / Japanese text has no spaces
      Case("explicit-scheme url glued to a leading CJK word is matched", "看这个https://b23.tv/abc 很好", listOf(web("https://b23.tv/abc"))),
      Case("CJK text glued after the url stays in the span", "看这个https://b23.tv/abc很好", listOf(web("https://b23.tv/abc很好"))),
      Case("explicit-scheme url glued to a leading kana word is matched", "これhttps://signal.org", listOf(web("https://signal.org"))),
      Case("url after newline is matched", "first line\nhttps://signal.org", listOf(web("https://signal.org"))),
      Case("url after open paren is matched", "(https://signal.org", listOf(web("https://signal.org"))),
      Case("url after digit-only token keeps boundary", "12345 https://signal.org", listOf(web("https://signal.org"))),
      Case("email's host half is not also matched as a url", "Hi user@example.com bye", emptyList()),

      // ----- false-positive guards -----
      Case("single dot in a sentence is not a url", "Hello. World", emptyList()),
      Case("decimal number is not a url", "value is 3.14", emptyList()),
      Case("version string is not a url", "v1.2.3 release", emptyList()),
      Case("single-letter TLD is rejected", "abc.x next", emptyList()),
      Case("bare scheme is not a url", "https:// is not a url", emptyList()),

      // ----- TLD validation against the IANA list -----
      Case("Mr.Smith style false positive is rejected", "Mr.Smith said hello", emptyList()),
      Case("bare domain with unknown TLD is rejected", "see signal.notatld today", emptyList()),
      Case("bare domain with valid IDN TLD is accepted", "visit example.рф", listOf(web("example.рф", url = "http://example.рф"))),
      Case("bare domain with punycode TLD is accepted", "visit example.xn--p1ai", listOf(web("example.xn--p1ai", url = "http://example.xn--p1ai"))),
      Case("fake punycode-shaped TLD is rejected", "see signal.xn--notarealtld today", emptyList()),
      Case("scheme with unknown TLD is still accepted", "https://buildbot.notatld/", listOf(web("https://buildbot.notatld/"))),
      Case("www-prefixed unknown TLD is still accepted", "www.foo.notatld", listOf(web("www.foo.notatld", url = "http://www.foo.notatld"))),
      Case("e.g. abbreviation is not a url", "e.g. you can do this", emptyList()),

      // ----- additional cases mirroring AOSP LinkifyTest -----
      Case("mixed-case scheme is matched", "hTtP://android.com", listOf(web("hTtP://android.com"))),
      Case("punycode url with scheme is matched", "http://xn--fsqu00a.xn--unup4y", listOf(web("http://xn--fsqu00a.xn--unup4y"))),
      Case("bare domain with leading dash on TLD label is rejected", "xn--fsqu00a.-xn--unup4y next", emptyList()),
      Case("tilde in path is preserved", "http://www.example.com:8080/~user/?foo=bar", listOf(web("http://www.example.com:8080/~user/?foo=bar"))),
      Case("dollar sign in path is preserved", "http://android.com/path\$?v=\$val", listOf(web("http://android.com/path\$?v=\$val"))),
      Case("port plus query is matched", "http://www.example.com:8080/?foo=bar", listOf(web("http://www.example.com:8080/?foo=bar"))),
      Case("schemed url with empty path and query is matched", "http://android.com?q=v", listOf(web("http://android.com?q=v"))),
      Case("bare domain with empty path and query is matched", "android.com?q=v", listOf(web("android.com?q=v", url = "http://android.com?q=v"))),
      Case("bare domain with internal dashes is matched", "see a-nd.r-oid.com today", listOf(web("a-nd.r-oid.com", url = "http://a-nd.r-oid.com"))),
      Case("bare domain with underscores is rejected", "a_nd.r_oid.com next", emptyList()),
      Case("schemed url with underscores in host is matched", "http://a_nd.r_oid.com/x", listOf(web("http://a_nd.r_oid.com/x"))),

      // ----- unicode whitespace acts as a URL boundary -----
      Case("en-space terminates url", "http://and rest", listOf(web("http://and"))),
      Case("ideographic space terminates url", "http://and　rest", listOf(web("http://and"))),
      Case("nbsp terminates url", "http://and rest", listOf(web("http://and"))),

      // ----- bidi/zero-width format chars on either side reject the url -----
      Case("leading bidi override rejects url", "‬moc.diordna.com next", emptyList()),
      Case("trailing bidi override rejects url", "moc.diordna.com‭ next", emptyList()),
      Case("internal bidi override rejects both halves", "moc.diordna.com‮moc.diordna.com", emptyList()),
      Case("zero-width space adjacent to url rejects it", "see signal.org​ next", emptyList()),
      // Tellomi (PM 2026-10-19): after Chinese sentence punctuation the link has already ended
      Case("zero-width space after CJK sentence punctuation ends the url at the punctuation", "https://zh.wikipedia.org/wiki/中国，\u200B很好", listOf(web("https://zh.wikipedia.org/wiki/中国"))),
      Case("bidi isolate after CJK closing quote ends the url at the quote", "「https://signal.org/a」\u2066", listOf(web("https://signal.org/a"))),
      Case("zero-width space glued to CJK text still rejects the url", "https://signal.org/a/中\u200B文", emptyList()),

      // ----- Tellomi (PM 2026-10-19, ADR-0063 §5.6): every hidden character (Cc/Cf/Co/Cs/Cn/Zl/Zp, as iOS and Desktop) -----
      Case("soft hyphen inside the path rejects the url", "https://signal.org/a\u00ADb", emptyList()),
      Case("soft hyphen inside the host rejects the url", "https://sig\u00ADnal.org/a", emptyList()),
      Case("soft hyphen inside CJK path rejects the url", "看 https://signal.org/a/中\u00AD文 吧", emptyList()),
      Case("soft hyphen right after the url rejects it", "https://signal.org/abc\u00AD", emptyList()),
      Case("soft hyphen before the url rejects it", "\u00ADhttps://signal.org/abc", emptyList()),
      Case("soft hyphen after ascii punctuation does not belong to the url", "https://signal.org/abc.\u00AD", listOf(web("https://signal.org/abc"))),
      Case("soft hyphen after CJK punctuation ends the url at the punctuation", "https://zh.wikipedia.org/wiki/中国，\u00AD很好", listOf(web("https://zh.wikipedia.org/wiki/中国"))),
      Case("soft hyphen after a space does not belong to the url", "https://signal.org/abc \u00AD", listOf(web("https://signal.org/abc"))),
      Case("mongolian vowel separator inside the url rejects it", "https://signal.org/a\u180Eb", emptyList()),
      Case("arabic letter mark inside the url rejects it", "https://signal.org/a\u061Cb", emptyList()),
      Case("interlinear annotation inside the url rejects it", "https://signal.org/a\uFFF9b", emptyList()),
      Case("C0 control inside the url rejects it", "https://signal.org/a\u0001b", emptyList()),
      Case("information separator inside the url rejects it", "https://signal.org/a\u001Fb", emptyList()),
      Case("C1 control inside the url rejects it", "https://signal.org/a\u0090b", emptyList()),
      Case("DEL inside the url rejects it", "https://signal.org/a\u007Fb", emptyList()),
      Case("private use character inside the url rejects it", "https://signal.org/a\uE000b", emptyList()),
      Case("unassigned U+2065 inside the url rejects it", "https://signal.org/a\u2065b", emptyList()),
      Case("lone surrogate inside the url rejects it", "https://signal.org/a\uD800b", emptyList()),
      Case("tag character (supplementary) inside the url rejects it", "https://signal.org/a\uDB40\uDC01b", emptyList()),
      Case("tag character (supplementary) before the url rejects it", "\uDB40\uDC01https://signal.org/abc", emptyList()),
      Case("supplementary private use character right after the url rejects it", "https://signal.org/abc\uDB80\uDC00", emptyList()),
      Case("NEL after the url is a normal end", "https://signal.org/abc\u0085next", listOf(web("https://signal.org/abc"))),
      Case("line separator after the url is a normal end", "https://signal.org/abc\u2028next", listOf(web("https://signal.org/abc"))),
      Case("tab after the url is a normal end", "https://signal.org/abc\tnext", listOf(web("https://signal.org/abc"))),
      Case("CRLF after a CJK url is a normal end", "https://signal.org/a/中文\r\n下一行", listOf(web("https://signal.org/a/中文"))),

      // ----- Tellomi (PM 2026-10-24): full-width Chinese punctuation / closing brackets end the link -----
      Case("cjk full stop after url is excluded", "看 https://x.com/abc。", listOf(web("https://x.com/abc"))),
      Case("cjk comma mid-text ends the url", "https://x.com/abc，后面", listOf(web("https://x.com/abc"))),
      Case("cjk enumeration comma ends the url", "https://x.com/a、https://y.com/b", listOf(web("https://x.com/a"), web("https://y.com/b"))),
      Case("cjk exclamation ends the url", "快看https://x.com/abc！真的", listOf(web("https://x.com/abc"))),
      Case("cjk question mark ends the url", "这是https://x.com/abc？", listOf(web("https://x.com/abc"))),
      Case("cjk semicolon ends the url", "https://x.com/abc；下一个", listOf(web("https://x.com/abc"))),
      Case("cjk colon ends the url", "https://x.com/abc：说明", listOf(web("https://x.com/abc"))),
      Case("cjk corner bracket wraps the url", "「https://x.com/abc」", listOf(web("https://x.com/abc"))),
      Case("cjk parenthesis wraps the url", "（https://x.com/abc）", listOf(web("https://x.com/abc"))),
      Case("cjk lenticular bracket wraps the url", "【https://x.com/abc】", listOf(web("https://x.com/abc"))),
      Case("cjk punctuation right after the host ends the url", "https://x.com，后面", listOf(web("https://x.com"))),
      Case("cjk punctuation in query ends the url", "https://x.com/s?q=1，后面", listOf(web("https://x.com/s?q=1"))),
      Case("several cjk punctuation marks are all excluded", "https://x.com/abc）。", listOf(web("https://x.com/abc"))),
      Case("chinese path before cjk comma is kept", "https://zh.wikipedia.org/wiki/中国，很好", listOf(web("https://zh.wikipedia.org/wiki/中国"))),
      Case("www url with space before cjk full stop", "去 www.x.com。", listOf(web("www.x.com", url = "http://www.x.com"))),
      Case("bare domain before cjk comma", "打开 signal.org，然后", listOf(web("signal.org", url = "http://signal.org"))),
      Case("cjk ellipsis after url is excluded", "看 https://x.com/abc…", listOf(web("https://x.com/abc"))),
      Case("cjk em dash mid-text ends the url", "https://x.com/abc—后面", listOf(web("https://x.com/abc"))),
      Case("fullwidth hyphen-minus after url is excluded", "看 https://x.com/abc－", listOf(web("https://x.com/abc"))),
      Case("fullwidth full stop after url is excluded", "看 https://x.com/abc．后面", listOf(web("https://x.com/abc"))),
      Case("mixed ascii period then cjk full stop", "Visit https://signal.org.。", listOf(web("https://signal.org"))),
      Case("ascii comma inside path then cjk ellipsis", "https://x.com/a,b…", listOf(web("https://x.com/a,b"))),
      Case("ascii comma inside path is still kept", "https://x.com/a,b", listOf(web("https://x.com/a,b"))),
      Case("ascii paren pair inside path is still kept", "https://en.wikipedia.org/wiki/Foo_(bar)", listOf(web("https://en.wikipedia.org/wiki/Foo_(bar)"))),
      Case("emoji (surrogate pair) in the path is still part of the url", "https://signal.org/a/😀", listOf(web("https://signal.org/a/😀")))
    ).map { arrayOf<Any>(it) }
  }
}

/**
 * Tests for behavior that doesn't fit the row-based shape of [LinkifierTest] — exception contracts
 * on the public API.
 */
class LinkifierContractTest {

  @Test
  fun `DetectedLink with negative start throws`() {
    assertFailure { Linkifier.DetectedLink(-1, 0, "x") }.isInstanceOf<IllegalArgumentException>()
  }

  @Test
  fun `DetectedLink with end before start throws`() {
    assertFailure { Linkifier.DetectedLink(5, 4, "x") }.isInstanceOf<IllegalArgumentException>()
  }
}

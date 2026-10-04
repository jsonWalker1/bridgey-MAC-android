package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** BRIDGEY WEB HANDOFF POC: continuation URL construction (HTML id + W3C Text Fragment). */
class WebHandoffPocTest {
    @Test fun urlOnlyWhenNothingElseIsKnown() {
        assertEquals("https://example.com/a", continuationUrl("https://example.com/a#old", null, null))
    }

    @Test fun idAndShortTextBecomeOneFragment() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Bluetooth#mwHQ:~:text=personal%20area%20networks",
            continuationUrl("https://en.wikipedia.org/wiki/Bluetooth", "mwHQ", "personal area networks"),
        )
    }

    @Test fun longTextUsesStartEndForm() {
        val text = "one two three four five six seven eight nine ten"
        assertEquals("https://x.y/#:~:text=one%20two%20three%20four,seven%20eight%20nine%20ten", continuationUrl("https://x.y/", null, text))
    }

    @Test fun syntaxCharactersAreEncoded() {
        assertEquals("a%2Db%2Cc%26d", encodeTextFragment("a-b,c&d"))
        assertEquals("1918%E2%80%931919", encodeTextFragment("1918–1919"))
    }

    @Test fun prefixAndSuffixContextDisambiguateRepeatedText() {
        assertEquals(
            "https://x.y/#:~:text=fixed%20and%20mobile-,devices,-over%20short%20distances",
            continuationUrl("https://x.y/", null, "devices", prefix = "fixed and mobile", suffix = "over short distances"),
        )
    }
}

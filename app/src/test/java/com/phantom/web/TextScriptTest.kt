package com.phantom.web

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class TextScriptTest {

    @Test
    fun testBuildTextScript_handlesSpecialCharactersSafely() {
        val testInputs = listOf(
            """Double quote: "hello"""",
            """Backslash: \path\to\file""",
            "Newline:\nFirst line\nSecond line\r\nThird",
            "Script tag injection: </script><script>alert('xss')</script>",
            "Emoji and unicode: 🚀🔥🎉❤️💻",
            "Turkish characters: şçöğüıİ ŞÇÖĞÜI",
            """Complex combo: " ' \ / </script> şçö 🚀"""
        )

        for (input in testInputs) {
            val script = buildTextScript(input)
            // Ensure the generated script contains the JSONObject.quote representation
            val quoted = JSONObject.quote(input)
            assertTrue("Script must contain safely quoted value", script.contains("var v = $quoted;"))
            assertTrue("Script must be wrapped in IIFE", script.startsWith("(function() {"))
            assertTrue("Script must dispatch input event", script.contains("el.dispatchEvent(new Event('input', { bubbles: true }));"))
        }
    }
}

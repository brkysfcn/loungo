package com.phantom.server

import org.junit.Assert.*
import org.junit.Test

class TokenManagerTest {

    @Test
    fun testGenerateTokenLengthAndCharset() {
        val token = TokenManager.generateToken()
        // 16 bytes base64url without padding: ceil(16 * 4 / 3) = 22 characters
        assertEquals(22, token.length)
        assertTrue("Token should match base64url charset: $token", token.matches(Regex("^[A-Za-z0-9_-]+$")))

        val token2 = TokenManager.generateToken()
        assertNotEquals(token, token2)
    }

    @Test
    fun testValidateToken() {
        val validToken = TokenManager.generateToken()

        assertTrue(TokenManager.validateToken(validToken, validToken, allowDevToken = false))
        assertFalse(TokenManager.validateToken("wrong_token", validToken, allowDevToken = false))
        assertFalse(TokenManager.validateToken("", validToken, allowDevToken = false))
        assertFalse(TokenManager.validateToken(null, validToken, allowDevToken = false))

        // When allowDevToken is false, DEV_TOKEN should be rejected
        assertFalse(TokenManager.validateToken(DEV_TOKEN, validToken, allowDevToken = false))

        // When allowDevToken is true, DEV_TOKEN should be accepted
        assertTrue(TokenManager.validateToken(DEV_TOKEN, validToken, allowDevToken = true))
    }
}

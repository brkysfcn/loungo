package com.phantom.web

import org.junit.Assert.assertEquals
import org.junit.Test

class BackActionTest {

    @Test
    fun testPlayerPriority() {
        val action = backAction(
            playerOpen = true,
            fullscreen = true,
            popupVisible = true,
            popupCanBack = true,
            mainCanBack = true,
            fromRemote = true
        )
        assertEquals(BackAction.CLOSE_PLAYER, action)
    }

    @Test
    fun testFullscreenPriority() {
        val action = backAction(
            fullscreen = true,
            popupVisible = true,
            popupCanBack = true,
            mainCanBack = true,
            fromRemote = true
        )
        assertEquals(BackAction.CLOSE_FULLSCREEN, action)
    }

    @Test
    fun testPopupCanBackPriority() {
        val action = backAction(
            fullscreen = false,
            popupVisible = true,
            popupCanBack = true,
            mainCanBack = true,
            fromRemote = false
        )
        assertEquals(BackAction.POPUP_GO_BACK, action)
    }

    @Test
    fun testClosePopupPriority() {
        val action = backAction(
            fullscreen = false,
            popupVisible = true,
            popupCanBack = false,
            mainCanBack = true,
            fromRemote = false
        )
        assertEquals(BackAction.CLOSE_POPUP, action)
    }

    @Test
    fun testMainGoBack() {
        val action = backAction(
            fullscreen = false,
            popupVisible = false,
            popupCanBack = false,
            mainCanBack = true,
            fromRemote = false
        )
        assertEquals(BackAction.MAIN_GO_BACK, action)
    }

    @Test
    fun testKumandaNoHistoryMovesTaskToBack() {
        val action = backAction(
            fullscreen = false,
            popupVisible = false,
            popupCanBack = false,
            mainCanBack = false,
            fromRemote = true
        )
        assertEquals(BackAction.MOVE_TASK_TO_BACK, action)
    }

    @Test
    fun testPhoneNoHistoryDoesNothing() {
        val action = backAction(
            fullscreen = false,
            popupVisible = false,
            popupCanBack = false,
            mainCanBack = false,
            fromRemote = false
        )
        assertEquals(BackAction.NONE, action)
    }
}

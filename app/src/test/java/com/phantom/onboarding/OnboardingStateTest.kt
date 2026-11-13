package com.phantom.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.phantom.onboarding.OnboardingStep as S

class OnboardingStateTest {
    private fun toLastPage(s: OnboardingState) { repeat(OnboardingState.LAST_PAGE) { s.nextPage() } }

    private fun toPhone(s: OnboardingState) {
        toLastPage(s); s.startTour(); s.onCursorClickInside(); s.advance(); s.onMenuOpened(); s.advance()
    }

    @Test fun startsAtWelcomePage() {
        val s = OnboardingState()
        assertEquals(S.INTRO, s.step)
        assertEquals(0, s.page)
        assertEquals(0 to 5, s.progress())
    }

    @Test fun pagesGoForwardAndBack() {
        val s = OnboardingState()
        s.prevPage(); assertEquals(0, s.page) // no-op on WELCOME
        s.nextPage(); s.nextPage()
        assertEquals(2, s.page)
        assertEquals(2 to 5, s.progress())
        s.prevPage()
        assertEquals(1, s.page)
        repeat(10) { s.nextPage() }
        assertEquals(4, s.page) // clamped
        assertEquals(S.INTRO, s.step)
    }

    @Test fun backWalksPagesThenEndsOnWelcome() {
        val s = OnboardingState()
        s.nextPage(); s.nextPage()
        s.back(); assertEquals(1, s.page)
        s.back(); assertEquals(0, s.page)
        assertEquals(S.INTRO, s.step)
        s.back()
        assertTrue(s.isDone)
        assertEquals(FinishReason.BACKED_OUT, s.finishReason)
        assertEquals(S.INTRO, s.finishedAtStep)
    }

    @Test fun skipWorksOnEveryPage() {
        for (page in 0..OnboardingState.LAST_PAGE) {
            val s = OnboardingState()
            repeat(page) { s.nextPage() }
            s.skip()
            assertTrue(s.isDone)
            assertEquals(FinishReason.SKIPPED, s.finishReason)
        }
    }

    @Test fun tourAndOpenBrowserOnlyOnLastPage() {
        val s = OnboardingState()
        s.startTour(); s.openBrowser()
        assertEquals(S.INTRO, s.step)
        s.nextPage(); s.startTour(); s.openBrowser()
        assertEquals(S.INTRO, s.step)
        toLastPage(s)
        s.openBrowser()
        assertTrue(s.isDone)
        assertEquals(FinishReason.COMPLETED, s.finishReason)
        assertEquals(S.INTRO, s.finishedAtStep)
    }

    @Test fun lastPageStartTourGoesToCursorAndBackReturnsToLastPage() {
        val s = OnboardingState()
        toLastPage(s)
        s.startTour()
        assertEquals(S.CURSOR, s.step)
        s.back()
        assertEquals(S.INTRO, s.step)
        assertEquals(OnboardingState.LAST_PAGE, s.page)
    }

    @Test fun aboutEntryStartsAtWelcomeAndRunsSameFlow() {
        val s = OnboardingState.about()
        assertEquals(S.INTRO, s.step)
        assertEquals(0, s.page)
        toLastPage(s); s.startTour()
        assertEquals(S.CURSOR, s.step)
    }

    @Test fun pageNavigationIgnoredOutsideIntro() {
        val s = OnboardingState.replay()
        s.nextPage(); s.prevPage()
        assertEquals(S.CURSOR, s.step)
        assertEquals(0, s.page)
    }

    @Test fun introSkipFinishes() {
        val s = OnboardingState()
        s.skip()
        assertEquals(S.DONE, s.step)
        assertEquals(FinishReason.SKIPPED, s.finishReason)
        assertEquals(S.INTRO, s.finishedAtStep)
    }

    @Test fun introStartTourGoesToRemote() {
        val s = OnboardingState()
        toLastPage(s)
        s.startTour()
        assertEquals(S.CURSOR, s.step)
    }

    @Test fun remoteCursorThenMenu() {
        val s = OnboardingState.replay()
        s.advance() // not completed: no-op
        assertEquals(S.CURSOR, s.step)
        s.onMenuOpened() // wrong part: ignored
        assertFalse(s.completed)
        s.onCursorClickInside()
        assertTrue(s.completed)
        s.advance()
        assertEquals(S.MENU, s.step)
        assertFalse(s.completed)
        s.advance() // menu not opened yet
        assertEquals(S.MENU, s.step)
        s.onMenuOpened()
        assertTrue(s.completed)
        s.advance()
        assertEquals(S.PHONE, s.step)
    }

    @Test fun phoneLaterGoesToVideo() {
        val s = OnboardingState()
        toPhone(s)
        assertFalse(s.completed)
        s.later()
        assertEquals(S.VIDEO, s.step)
    }

    @Test fun phoneConnectedThenAdvance() {
        val s = OnboardingState()
        toPhone(s)
        s.advance() // not completed: no-op
        assertEquals(S.PHONE, s.step)
        s.onPhoneConnected()
        assertTrue(s.completed)
        s.advance()
        assertEquals(S.VIDEO, s.step)
    }

    @Test fun phoneAlreadyConnectedCompletesOnEntry() {
        val s = OnboardingState(phoneConnected = true)
        toPhone(s)
        assertTrue(s.completed)
        s.advance()
        assertEquals(S.VIDEO, s.step)
    }

    @Test fun videoFinishesCompleted() {
        val s = OnboardingState()
        toPhone(s); s.later()
        s.advance()
        assertTrue(s.isDone)
        assertEquals(FinishReason.COMPLETED, s.finishReason)
        assertEquals(S.VIDEO, s.finishedAtStep)
    }

    @Test fun eventsOutsideTheirStepAreIgnored() {
        val s = OnboardingState.replay()
        s.onPhoneConnected()
        assertFalse(s.completed)
        assertTrue(s.phoneConnected)
        s.onCursorClickInside(); s.advance()
        s.onCursorClickInside()
        assertFalse(s.completed)
        s.later() // only valid on PHONE
        assertEquals(S.MENU, s.step)
    }

    @Test fun backWalksPreviousSteps() {
        val s = OnboardingState()
        toPhone(s)
        s.back(); assertEquals(S.MENU, s.step)
        assertFalse(s.completed)
        s.back(); assertEquals(S.CURSOR, s.step)
        s.back(); assertEquals(S.INTRO, s.step)
        assertEquals(OnboardingState.LAST_PAGE, s.page)
        repeat(OnboardingState.LAST_PAGE) { s.back() }
        assertEquals(0, s.page)
        s.back()
        assertEquals(S.DONE, s.step)
        assertEquals(FinishReason.BACKED_OUT, s.finishReason)
    }

    @Test fun backFromVideoReturnsToPhone() {
        val s = OnboardingState()
        toPhone(s); s.onPhoneConnected(); s.advance()
        s.back()
        assertEquals(S.PHONE, s.step)
        assertTrue(s.completed) // phone still connected
    }

    @Test fun skipFromAnywhere() {
        val s = OnboardingState.replay()
        s.skip()
        assertEquals(S.DONE, s.step)
        assertEquals(FinishReason.SKIPPED, s.finishReason)
        assertEquals(S.CURSOR, s.finishedAtStep)
    }

    @Test fun replayStartsAtRemoteAndBackEnds() {
        val s = OnboardingState.replay()
        assertEquals(S.CURSOR, s.step)
        s.back()
        assertTrue(s.isDone)
        assertEquals(FinishReason.BACKED_OUT, s.finishReason)
    }

    @Test fun replayPhoneAlreadyConnected() {
        val s = OnboardingState.replay(phoneConnected = true)
        s.onCursorClickInside(); s.advance(); s.onMenuOpened(); s.advance()
        assertEquals(S.PHONE, s.step)
        assertTrue(s.completed)
        s.advance()
        assertEquals(S.VIDEO, s.step)
    }

    @Test fun progressDots() {
        val s = OnboardingState.replay()
        assertEquals(0 to 3, s.progress())
        s.onCursorClickInside(); s.advance()
        assertEquals(0 to 3, s.progress()) // menu part is still step 1/3
        s.onMenuOpened(); s.advance()
        assertEquals(1 to 3, s.progress())
        s.later()
        assertEquals(2 to 3, s.progress())
    }

    @Test fun nothingHappensAfterDone() {
        val s = OnboardingState()
        s.skip()
        s.startTour(); s.nextPage(); s.later(); s.back(); s.advance()
        assertEquals(S.DONE, s.step)
        assertEquals(FinishReason.SKIPPED, s.finishReason)
    }

    @Test
    fun phoneThatDisconnectedEarlierDoesNotCompletePhoneStep() {
        val s = OnboardingState.replay()
        s.onPhoneConnected()
        s.onPhoneDisconnected()
        s.onCursorClickInside(); s.advance()
        s.onMenuOpened(); s.advance()
        org.junit.Assert.assertEquals(S.PHONE, s.step)
        org.junit.Assert.assertFalse(s.completed)
    }
}

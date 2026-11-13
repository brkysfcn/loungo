package com.phantom.onboarding

/** Pure state machine for the first-run intro + tutorial (no Android imports). */
enum class OnboardingStep { INTRO, CURSOR, MENU, PHONE, VIDEO, DONE }
// INTRO is a showcase with pages 0..4 (WELCOME + 4 feature pages), see [OnboardingState.page].
// Linear flow: INTRO -> REMOTE (CURSOR then MENU, one step 1/3) -> PHONE (2/3) -> VIDEO (3/3) -> DONE.

enum class FinishReason { COMPLETED, SKIPPED, BACKED_OUT }

class OnboardingState(start: OnboardingStep = OnboardingStep.INTRO, phoneConnected: Boolean = false) {
    var step: OnboardingStep = start
        private set

    /** True once the current interactive step has been performed (waiting for advance()). */
    var completed: Boolean = false
        private set

    var phoneConnected: Boolean = phoneConnected
        private set

    var finishReason: FinishReason? = null
        private set

    /** The step that was showing when the flow ended (for the local log). */
    var finishedAtStep: OnboardingStep? = null
        private set

    /** Showcase page while on INTRO: 0 = WELCOME, 1..4 = feature pages. Kept when the tour is left again. */
    var page: Int = 0
        private set

    private val history = ArrayDeque<OnboardingStep>()

    val isDone: Boolean get() = step == OnboardingStep.DONE

    companion object {
        /** Replay from the menu: starts at the REMOTE step (CURSOR part), no intro. */
        fun replay(phoneConnected: Boolean = false) = OnboardingState(OnboardingStep.CURSOR, phoneConnected)

        /** "About Loungo" menu entry: showcase from the WELCOME page, then the same flow. */
        fun about(phoneConnected: Boolean = false) = OnboardingState(OnboardingStep.INTRO, phoneConnected)

        const val PAGE_COUNT = 5
        const val LAST_PAGE = PAGE_COUNT - 1
    }

    /** INTRO: next showcase page (no-op on the last page). */
    fun nextPage() { if (step == OnboardingStep.INTRO && page < LAST_PAGE) page++ }

    /** INTRO: previous showcase page (no-op on WELCOME; Back there ends the flow). */
    fun prevPage() { if (step == OnboardingStep.INTRO && page > 0) page-- }

    /** INTRO last page -> "Kısa turu başlat" */
    fun startTour() { if (step == OnboardingStep.INTRO && page == LAST_PAGE) goTo(OnboardingStep.CURSOR) }

    /** PHONE -> "Daha sonra" */
    fun later() { if (step == OnboardingStep.PHONE) goTo(OnboardingStep.VIDEO) }

    fun onPhoneConnected() {
        phoneConnected = true
        if (step == OnboardingStep.PHONE) completed = true
    }

    /** Only a phone that is connected right now counts; a short visit earlier does not. */
    fun onPhoneDisconnected() { phoneConnected = false }

    fun onCursorClickInside() { if (step == OnboardingStep.CURSOR) completed = true }

    fun onMenuOpened() { if (step == OnboardingStep.MENU) completed = true }

    /** Moves past a completed step (VIDEO always advances: its button is the action). */
    fun advance() {
        if (step != OnboardingStep.VIDEO && !completed) return
        when (step) {
            OnboardingStep.PHONE -> goTo(OnboardingStep.VIDEO)
            OnboardingStep.CURSOR -> goTo(OnboardingStep.MENU)
            OnboardingStep.MENU -> goTo(OnboardingStep.PHONE)
            OnboardingStep.VIDEO -> finish(FinishReason.COMPLETED)
            else -> {}
        }
    }

    fun skip() { if (!isDone) finish(FinishReason.SKIPPED) }

    /** "Tarayıcıyı aç" on the last showcase page: ends the flow as completed. */
    fun openBrowser() { if (step == OnboardingStep.INTRO && page == LAST_PAGE) finish(FinishReason.COMPLETED) }

    /** Previous showcase page / step; on the first visible step the flow ends (counts as seen). */
    fun back() {
        if (isDone) return
        if (step == OnboardingStep.INTRO && page > 0) { page--; return }
        val prev = history.removeLastOrNull()
        if (prev == null) finish(FinishReason.BACKED_OUT) else enter(prev)
    }

    /** Progress dots: (index, total); INTRO shows the showcase pages. Null on DONE. CURSOR and MENU are both step 1/3. */
    fun progress(): Pair<Int, Int>? = when (step) {
        OnboardingStep.INTRO -> page to PAGE_COUNT
        OnboardingStep.CURSOR, OnboardingStep.MENU -> 0 to 3
        OnboardingStep.PHONE -> 1 to 3
        OnboardingStep.VIDEO -> 2 to 3
        else -> null
    }

    private fun goTo(next: OnboardingStep) {
        history.addLast(step)
        enter(next)
    }

    private fun enter(next: OnboardingStep) {
        step = next
        // Already-connected phone: the PHONE step is satisfied as soon as it is shown.
        completed = next == OnboardingStep.PHONE && phoneConnected
    }

    private fun finish(reason: FinishReason) {
        finishedAtStep = step
        finishReason = reason
        step = OnboardingStep.DONE
        completed = false
    }
}

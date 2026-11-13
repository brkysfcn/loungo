package com.phantom.web

enum class BackAction {
    CLOSE_PLAYER,
    CLOSE_FULLSCREEN,
    POPUP_GO_BACK,
    CLOSE_POPUP,
    MAIN_GO_BACK,
    MOVE_TASK_TO_BACK,
    NONE
}

fun backAction(
    playerOpen: Boolean = false,
    fullscreen: Boolean,
    popupVisible: Boolean,
    popupCanBack: Boolean,
    mainCanBack: Boolean,
    fromRemote: Boolean
): BackAction {
    // 0. Oynatıcı açıksa kapat
    if (playerOpen) {
        return BackAction.CLOSE_PLAYER
    }
    // 1. Tam ekran açıksa kapat
    if (fullscreen) {
        return BackAction.CLOSE_FULLSCREEN
    }
    // 2. Görünür pop-up varsa: geçmişi varsa onda goBack, yoksa pop-up'ı kapat
    if (popupVisible) {
        return if (popupCanBack) BackAction.POPUP_GO_BACK else BackAction.CLOSE_POPUP
    }
    // 3. Ana sayfada goBack
    if (mainCanBack) {
        return BackAction.MAIN_GO_BACK
    }
    // 4. Yalnızca kumandada ve geçmiş yoksa moveTaskToBack(true)
    if (fromRemote) {
        return BackAction.MOVE_TASK_TO_BACK
    }
    return BackAction.NONE
}

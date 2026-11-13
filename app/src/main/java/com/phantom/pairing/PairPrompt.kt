package com.phantom.pairing

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phantom.R
import com.phantom.server.Hub
import com.phantom.server.PairRequests
import com.phantom.server.TokenManager

/**
 * "Connect this phone?" on whichever TV screen is on top (browser or player).
 * A dialog has its own window, so the remote's OK / D-pad / Back go to it, not to the
 * cursor or the player underneath. Wired from PhantomApplication's TV activity lifecycle.
 */
object PairPrompt {
    private const val TIMEOUT_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())
    private var owner: Activity? = null
    private var dialog: AlertDialog? = null

    fun attach(activity: Activity) {
        owner = activity
        Hub.pairPromptHandler = { req -> main.post { show(req) } }
    }

    /** Only the current owner detaches: the next screen starts before the previous one stops. */
    fun detach(activity: Activity) {
        if (owner !== activity) return
        owner = null
        Hub.pairPromptHandler = null
        dialog?.let { if (it.isShowing) it.dismiss() } // dismiss listener answers "no"
        dialog = null
    }

    private fun show(req: PairRequests.Pending) {
        val a = owner ?: run { Hub.pairRequests.answer(req.id, false); return }
        if (a.isFinishing || a.isDestroyed) { Hub.pairRequests.answer(req.id, false); return }
        dialog?.let { if (it.isShowing) it.dismiss() }

        var approved = false
        val d = AlertDialog.Builder(a)
            .setTitle(R.string.pair_prompt_title)
            .setMessage(req.name)
            .setPositiveButton(R.string.pair_allow) { _, _ -> approved = true }
            .setNegativeButton(R.string.pair_deny, null)
            .create()
        d.setOnDismissListener {
            if (approved) TokenManager.setPaired(a.applicationContext, true)
            Hub.pairRequests.answer(req.id, approved)
            Log.i("PHANTOM", "PAIR_PROMPT answered approved=$approved")
            if (dialog === d) dialog = null
        }
        dialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        Log.i("PHANTOM", "PAIR_PROMPT shown on=${a.javaClass.simpleName}")

        val left = (TIMEOUT_MS - (System.currentTimeMillis() - req.createdAt)).coerceAtLeast(0)
        main.postDelayed({ if (d.isShowing) d.dismiss() }, left)
    }
}

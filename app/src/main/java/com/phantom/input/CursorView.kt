package com.phantom.input

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    init {
        setImageResource(com.phantom.R.drawable.ic_cursor)
        isClickable = false
        isFocusable = false
    }
}

package org.mozilla.tv.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton

/** Keep TV focus visible without making the first pointer click only take focus. */
class TvToolbarButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }

        return super.onTouchEvent(event)
    }
}

class TvToolbarImageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }

        return super.onTouchEvent(event)
    }
}

package org.mozilla.tv.firefox.ext

/** JavaScript hooks used by the original Fire TV video and focus controls. */
object Js {
    const val CACHE_JS = "window.__firefoxTvFocusedElement = document.activeElement;"
    const val RESTORE_JS = "if (window.__firefoxTvFocusedElement && document.contains(window.__firefoxTvFocusedElement)) window.__firefoxTvFocusedElement.focus();"
    const val PAUSE_VIDEO = "document.querySelectorAll('video').forEach(function(v) { v.pause(); });"
    const val JS_OBSERVE_PLAYBACK_STATE = "void 0;"
    const val BODY_ELEMENT_FOCUSED = "document.activeElement === document.body"
    const val NO_ELEMENT_FOCUSED = "!document.activeElement"
    const val SIDEBAR_FOCUSED = "false"
}

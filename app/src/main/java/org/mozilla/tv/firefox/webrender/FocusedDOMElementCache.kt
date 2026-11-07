package org.mozilla.tv.firefox.webrender

import mozilla.components.concept.engine.EngineView

/** Retains DOM focus across TV overlay focus changes. */
class FocusedDOMElementCache(private val engineView: EngineView) {
    fun cache() { /* Engine-specific DOM scripting must be wired separately. */ }
    fun restore() { /* Engine-specific DOM scripting must be wired separately. */ }
}

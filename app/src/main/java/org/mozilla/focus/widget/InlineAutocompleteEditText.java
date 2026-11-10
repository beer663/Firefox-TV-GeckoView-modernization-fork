/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.widget;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.mozilla.focus.utils.UrlUtils;
import org.mozilla.tv.firefox.R;

public class InlineAutocompleteEditText
        extends androidx.appcompat.widget.AppCompatEditText {

    public interface OnCommitListener {
        void onCommit();
    }

    public interface OnFilterListener {
        void onFilter(String searchText, InlineAutocompleteEditText view);
    }

    public interface OnUserInputListener {
        void onUserInput();
    }

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    public static class AutocompleteResult {
        private final String text;
        private final String source;
        private final int totalItems;

        public static AutocompleteResult emptyResult() {
            return new AutocompleteResult("", "", 0);
        }

        public AutocompleteResult(
                @NonNull String text,
                @NonNull String source,
                int totalItems) {
            this.text = text;
            this.source = source;
            this.totalItems = totalItems;
        }

        public boolean isEmpty() {
            return text.isEmpty();
        }

        public String getText() {
            return text;
        }

        public String getSource() {
            return source;
        }

        public int getTotalItems() {
            return totalItems;
        }

        public int getLength() {
            return text.length();
        }

        public boolean startsWith(String text) {
            return this.text.startsWith(text);
        }
    }

    private static final NoCopySpan AUTOCOMPLETE_SPAN =
            new NoCopySpan.Concrete();

    private final TextWatcher mTextChangeListener =
            new TextChangeListener();

    private boolean mEnterKeyDown;
    private boolean mSubmitOnEnterUp;

    private OnCommitListener mCommitListener;
    private OnFilterListener mFilterListener;
    private OnUserInputListener mUserInputListener;
    private OnBackPressedListener mOnBackPressedListener;

    private AutocompleteResult mAutoCompleteResult =
            AutocompleteResult.emptyResult();

    private boolean mSettingAutoComplete;
    private Object[] mAutoCompleteSpans;
    private boolean mDiscardAutoCompleteResult;
    private boolean isKeyFromRemoteAppOrSoftKeyboardClear;

    public InlineAutocompleteEditText(
            Context context,
            AttributeSet attrs) {
        super(context, attrs);

        setShowSoftInputOnFocus(true);
        setOnKeyListener(new KeyListener());

        setOnEditorActionListener((view, actionId, event) -> {
            // Raw Enter keys are handled after the keyboard sees them.
            if (event != null) {
                return false;
            }

            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput();
                return true;
            }

            return false;
        });
    }

    public void setOnCommitListener(OnCommitListener listener) {
        mCommitListener = listener;
    }

    public void setOnFilterListener(OnFilterListener listener) {
        mFilterListener = listener;
    }

    public void setOnUserInputListener(OnUserInputListener listener) {
        mUserInputListener = listener;
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        mOnBackPressedListener = listener;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        addTextChangedListener(mTextChangeListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeTextChangedListener(mTextChangeListener);
        mEnterKeyDown = false;
        super.onDetachedFromWindow();
    }

    @Override
    public void onFocusChanged(
            boolean gainFocus,
            int direction,
            Rect previouslyFocusedRect) {
        super.onFocusChanged(
                gainFocus,
                direction,
                previouslyFocusedRect);

        if (gainFocus) {
            resetAutocompleteState();
            return;
        }

        removeAutocomplete(getText());
        mEnterKeyDown = false;

        // Let Android manage the keyboard during focus transitions.
        // Do not restart input or forcibly hide the keyboard here.
    }

    @Override
    public void setText(
            CharSequence text,
            TextView.BufferType type) {
        super.setText(text, type);
        resetAutocompleteState();
    }

    @Override
    public void sendAccessibilityEventUnchecked(
            AccessibilityEvent event) {
        if (event.getEventType()
                == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                && getParent() != null
                && !isShown()) {
            onInitializeAccessibilityEvent(event);
            dispatchPopulateAccessibilityEvent(event);
            getParent().requestSendAccessibilityEvent(this, event);
        } else {
            super.sendAccessibilityEventUnchecked(event);
        }
    }

    private void beginSettingAutocomplete() {
        beginBatchEdit();
        mSettingAutoComplete = true;
    }

    private void endSettingAutocomplete() {
        mSettingAutoComplete = false;
        endBatchEdit();
    }

    private void resetAutocompleteState() {
        mAutoCompleteSpans = new Object[] {
                AUTOCOMPLETE_SPAN,
                new BackgroundColorSpan(
                        ContextCompat.getColor(
                                getContext(),
                                R.color.colorAccent))
        };

        mAutoCompleteResult = AutocompleteResult.emptyResult();
    }

    private static String getNonAutocompleteText(Editable text) {
        int start = text.getSpanStart(AUTOCOMPLETE_SPAN);

        if (start < 0) {
            return text.toString();
        }

        return TextUtils.substring(text, 0, start);
    }

    private boolean removeAutocomplete(Editable text) {
        int start = text.getSpanStart(AUTOCOMPLETE_SPAN);

        if (start < 0) {
            return false;
        }

        beginSettingAutocomplete();
        text.delete(start, text.length());
        endSettingAutocomplete();

        return true;
    }

    private boolean commitAutocomplete(Editable text) {
        int start = text.getSpanStart(AUTOCOMPLETE_SPAN);

        if (start < 0) {
            return false;
        }

        beginSettingAutocomplete();

        for (Object span : mAutoCompleteSpans) {
            text.removeSpan(span);
        }

        endSettingAutocomplete();

        if (mFilterListener != null) {
            mFilterListener.onFilter(text.toString(), null);
        }

        return true;
    }

    public void onAutocomplete(AutocompleteResult result) {
        if (mDiscardAutoCompleteResult
                || isKeyFromRemoteAppOrSoftKeyboardClear) {
            return;
        }

        if (!isEnabled() || result == null || result.isEmpty()) {
            mAutoCompleteResult = AutocompleteResult.emptyResult();
            return;
        }

        Editable text = getText();
        int textLength = text.length();
        int resultLength = result.getLength();
        int autoCompleteStart =
                text.getSpanStart(AUTOCOMPLETE_SPAN);

        mAutoCompleteResult = result;

        if (autoCompleteStart > -1) {
            if (!TextUtils.regionMatches(
                    result.getText(),
                    0,
                    text,
                    0,
                    autoCompleteStart)) {
                return;
            }

            beginSettingAutocomplete();

            text.replace(
                    autoCompleteStart,
                    textLength,
                    result.getText(),
                    autoCompleteStart,
                    resultLength);

            endSettingAutocomplete();
        } else {
            if (resultLength <= textLength
                    || !TextUtils.regionMatches(
                            result.getText(),
                            0,
                            text,
                            0,
                            textLength)) {
                return;
            }

            Object[] spans =
                    text.getSpans(
                            textLength,
                            textLength,
                            Object.class);

            int[] spanStarts = new int[spans.length];
            int[] spanEnds = new int[spans.length];
            int[] spanFlags = new int[spans.length];

            for (int i = 0; i < spans.length; i++) {
                Object span = spans[i];
                int spanFlag = text.getSpanFlags(span);

                if ((spanFlag & Spanned.SPAN_COMPOSING) == 0
                        && span != Selection.SELECTION_START
                        && span != Selection.SELECTION_END) {
                    continue;
                }

                spanStarts[i] = text.getSpanStart(span);
                spanEnds[i] = text.getSpanEnd(span);
                spanFlags[i] = spanFlag;
            }

            beginSettingAutocomplete();

            text.append(
                    result.getText(),
                    textLength,
                    resultLength);

            for (int i = 0; i < spans.length; i++) {
                int spanFlag = spanFlags[i];

                if (spanFlag == 0) {
                    continue;
                }

                text.setSpan(
                        spans[i],
                        spanStarts[i],
                        spanEnds[i],
                        spanFlag);
            }

            for (Object span : mAutoCompleteSpans) {
                text.setSpan(
                        span,
                        textLength,
                        resultLength,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            bringPointIntoView(resultLength);
            endSettingAutocomplete();
        }

        announceForAccessibility(text.toString());
    }

    @NonNull
    public AutocompleteResult getLastAutocompleteResult() {
        return mAutoCompleteResult;
    }

    private static boolean hasCompositionString(Editable content) {
        Object[] spans =
                content.getSpans(0, content.length(), Object.class);

        if (spans != null) {
            for (Object span : spans) {
                if ((content.getSpanFlags(span)
                        & Spanned.SPAN_COMPOSING) != 0) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public InputConnection onCreateInputConnection(
            EditorInfo outAttrs) {
        InputConnection connection =
                super.onCreateInputConnection(outAttrs);

        if (connection == null) {
            return null;
        }

        return new InputConnectionWrapper(connection, false) {
            @Override
            public boolean deleteSurroundingText(
                    int beforeLength,
                    int afterLength) {
                removeAutocomplete(getText());

                return super.deleteSurroundingText(
                        beforeLength,
                        afterLength);
            }

            private boolean removeAutocompleteOnComposing(
                    CharSequence text) {
                Editable editable = getText();

                int composingStart =
                        BaseInputConnection.getComposingSpanStart(
                                editable);

                int composingEnd =
                        BaseInputConnection.getComposingSpanEnd(
                                editable);

                if (composingStart >= 0
                        && composingEnd >= 0
                        && composingEnd - composingStart
                                > text.length()
                        && removeAutocomplete(editable)) {
                    finishComposingText();
                    setComposingRegion(
                            composingStart,
                            composingEnd);
                    return true;
                }

                return false;
            }

            @Override
            public boolean commitText(
                    CharSequence text,
                    int newCursorPosition) {
                if (mUserInputListener != null) {
                    mUserInputListener.onUserInput();
                }

                if ("".equals(text)) {
                    setIsKeyFromRemoteAppOrSoftKeyboardClear(true);
                }

                if (removeAutocompleteOnComposing(text)) {
                    return false;
                }

                return super.commitText(
                        text,
                        newCursorPosition);
            }

            @Override
            public boolean setComposingText(
                    CharSequence text,
                    int newCursorPosition) {
                if (removeAutocompleteOnComposing(text)) {
                    return false;
                }

                return super.setComposingText(
                        text,
                        newCursorPosition);
            }
        };
    }

    private class TextChangeListener implements TextWatcher {
        private int textLengthBeforeChange;

        @Override
        public void afterTextChanged(Editable editable) {
            if (!isEnabled() || mSettingAutoComplete) {
                return;
            }

            String text = getNonAutocompleteText(editable);
            int textLength = text.length();
            boolean doAutocomplete = true;

            if (UrlUtils.isSearchQuery(text)
                    || isKeyFromRemoteAppOrSoftKeyboardClear) {
                doAutocomplete = false;
            } else if (textLength
                    == textLengthBeforeChange - 1
                    || textLength == 0) {
                doAutocomplete = false;
            }

            mDiscardAutoCompleteResult = !doAutocomplete;

            if (doAutocomplete
                    && mAutoCompleteResult.startsWith(text)) {
                onAutocomplete(mAutoCompleteResult);
                doAutocomplete = false;
            } else {
                removeAutocomplete(editable);
            }

            if (mFilterListener != null) {
                mFilterListener.onFilter(
                        text,
                        doAutocomplete
                                ? InlineAutocompleteEditText.this
                                : null);
            }
        }

        @Override
        public void beforeTextChanged(
                CharSequence text,
                int start,
                int count,
                int after) {
            textLengthBeforeChange = text.length();
        }

        @Override
        public void onTextChanged(
                CharSequence text,
                int start,
                int before,
                int count) {
        }
    }

    private void submitInput() {
        Editable text = getText();

        if (text != null
                && TextUtils.getTrimmedLength(text) > 0
                && mCommitListener != null) {
            mCommitListener.onCommit();
        }
    }

    private boolean isSoftwareKeyboardVisible() {
        WindowInsetsCompat insets =
                ViewCompat.getRootWindowInsets(this);

        if (insets != null
                && insets.isVisible(
                        WindowInsetsCompat.Type.ime())) {
            return true;
        }

        // Fallback for older TV keyboards without IME insets.
        Rect visible = new Rect();
        View root = getRootView();

        root.getWindowVisibleDisplayFrame(visible);

        int obscured =
                root.getHeight() - visible.height();

        int minimum =
                (int) (120
                        * getResources()
                                .getDisplayMetrics().density);

        return obscured
                > Math.max(
                        minimum,
                        root.getHeight() / 5);
    }

    private void openKeyboard() {
        if (!hasFocus() && !requestFocus()) {
            return;
        }

        post(() -> {
            if (!isAttachedToWindow()
                    || !hasFocus()
                    || !hasWindowFocus()) {
                return;
            }

            InputMethodManager imm =
                    (InputMethodManager) getContext()
                            .getSystemService(
                                    Context.INPUT_METHOD_SERVICE);

            if (imm != null) {
                imm.showSoftInput(
                        this,
                        InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private class KeyListener implements View.OnKeyListener {
        @Override
        public boolean onKey(
                View view,
                int keyCode,
                KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode
                            == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getAction()
                        == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        mEnterKeyDown = true;

                        InputDevice device =
                                event.getDevice();

                        boolean physicalKeyboard =
                                device != null
                                        && device.getKeyboardType()
                                                == InputDevice
                                                .KEYBOARD_TYPE_ALPHABETIC;

                        mSubmitOnEnterUp =
                                isSoftwareKeyboardVisible()
                                        || physicalKeyboard;
                    }

                    return true;
                }

                if (event.getAction()
                        == KeyEvent.ACTION_UP) {
                    boolean handledDown = mEnterKeyDown;
                    mEnterKeyDown = false;

                    if (handledDown && !event.isCanceled()) {
                        if (!mSubmitOnEnterUp) {
                            openKeyboard();
                        } else if (!hasCompositionString(
                                getText())) {
                            submitInput();
                        }
                    }

                    return true;
                }

                return false;
            }

            if ((keyCode == KeyEvent.KEYCODE_DEL
                    || keyCode
                            == KeyEvent.KEYCODE_FORWARD_DEL)
                    && removeAutocomplete(getText())) {
                return true;
            }

            return false;
        }
    }

    @Override
    public boolean onKeyPreIme(
            int keyCode,
            KeyEvent event) {
        setIsKeyFromRemoteAppOrSoftKeyboardClear(false);

        // Let the TV keyboard process Enter and direction keys.
        // Android handles Back to dismiss the keyboard.
        if (keyCode == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP
                && !event.isCanceled()) {
            removeAutocomplete(getText());

            if (mOnBackPressedListener != null) {
                mOnBackPressedListener.onBackPressed();
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    protected void onSelectionChanged(
            int selectionStart,
            int selectionEnd) {
        if (isAttachedToWindow()) {
            Editable text = getText();
            int start =
                    text.getSpanStart(AUTOCOMPLETE_SPAN);

            if (mSettingAutoComplete
                    || start < 0
                    || start == selectionStart
                            && start == selectionEnd) {
                return;
            }

            if (selectionStart <= start
                    && selectionEnd <= start) {
                removeAutocomplete(text);
            } else {
                commitAutocomplete(text);
            }
        }

        super.onSelectionChanged(
                selectionStart,
                selectionEnd);
    }

    private void setIsKeyFromRemoteAppOrSoftKeyboardClear(
            boolean isKeyFromRemoteApp) {
        isKeyFromRemoteAppOrSoftKeyboardClear =
                isKeyFromRemoteApp;

        if (isKeyFromRemoteApp) {
            resetAutocompleteState();
            removeAutocomplete(getText());
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        boolean direction =
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP;

        if (direction && !isSoftwareKeyboardVisible()) {
            return false;
        }

        return super.dispatchKeyEvent(event);
    }
}

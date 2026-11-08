/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

"use strict";

(() => {
  let focusedElement = null;

  const rememberFocus = () => {
    const element = document.activeElement;

    if (
      element &&
      element !== document.body &&
      element !== document.documentElement
    ) {
      focusedElement = element;
    }
  };

  // Remember the selected page element before an Android overlay takes focus.
  document.addEventListener("focusin", rememberFocus, true);

  let port;
  let sequence = Promise.resolve();

  const onCommand = message => {
    // Preserve the order of asynchronous page operations.
    sequence = sequence
      .then(async () => {
        if (message.action === "cacheFocus") {
          rememberFocus();
        } else if (message.action === "restoreFocus") {
          if (focusedElement && focusedElement.isConnected) {
            focusedElement.focus({preventScroll: true});
          }
        } else if (
          message.action === "eval" ||
          message.action === "pauseMedia"
        ) {
          await browser.runtime.sendMessage(message);
        }
      })
      .catch(error => {
        console.warn("Firefox TV page operation failed", error);
      });
  };

  const connect = () => {
    if (port) {
      port.disconnect();
    }

    port = browser.runtime.connectNative("tvPageControls");
    port.onMessage.addListener(onCommand);
  };

  connect();

  // Back/forward navigation can restore a cached document without reinjection.
  window.addEventListener("pageshow", event => {
    if (event.persisted) {
      connect();
    }
  });
})();

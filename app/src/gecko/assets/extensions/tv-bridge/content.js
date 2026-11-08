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

  document.addEventListener("focusin", rememberFocus, true);

  let port;
  let sequence = Promise.resolve();

  const collectSample = async message => {
    const replyPort = port;
    let timer;

    try {
      const frames = await Promise.race([
        browser.runtime.sendMessage({
          action: "collectPlayback"
        }),
        new Promise((_, reject) => {
          timer = setTimeout(
            () => reject(new Error("Sample timeout")),
            4000
          );
        })
      ]);

      replyPort.postMessage({
        action: "playbackSample",
        id: message.id,
        frames
      });
    } catch (_) {
      // Error messages can contain page addresses, so do not record them.
      try {
        replyPort.postMessage({
          action: "playbackSample",
          id: message.id,
          error: true
        });
      } catch (_) {
        // The document or native session has already closed.
      }
    } finally {
      clearTimeout(timer);
    }
  };

  const onCommand = message => {
    if (message.action === "collectPlayback") {
      // Diagnostic requests cannot block focus or media controls.
      collectSample(message);
      return;
    }

    sequence = sequence
      .then(async () => {
        if (message.action === "cacheFocus") {
          rememberFocus();
        } else if (message.action === "restoreFocus") {
          if (focusedElement && focusedElement.isConnected) {
            focusedElement.focus({
              preventScroll: true
            });
          }
        } else if (
          message.action === "eval" ||
          message.action === "pauseMedia"
        ) {
          await browser.runtime.sendMessage(message);
        }
      })
      .catch(error => {
        console.warn(
          "Firefox TV page operation failed",
          error
        );
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

  window.addEventListener("pageshow", event => {
    if (event.persisted) {
      connect();
    }
  });
})();

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

"use strict";

// Only this extension's own top-level content script can request operations.
// No page-visible Java interface or window.postMessage bridge is exposed.
browser.runtime.onMessage.addListener((message, sender) => {
  if (
    sender.id !== browser.runtime.id ||
    !sender.tab ||
    sender.frameId !== 0
  ) {
    return undefined;
  }

  if (message.action === "eval" && typeof message.code === "string") {
    return browser.tabs.executeScript(sender.tab.id, {
      code: message.code,
      frameId: 0,
      runAt: "document_start"
    });
  }

  if (message.action === "pauseMedia") {
    return browser.tabs.executeScript(sender.tab.id, {
      code:
        'document.querySelectorAll("video, audio")' +
        ".forEach(media => media.pause());",
      allFrames: true,
      runAt: "document_start"
    });
  }

  return undefined;
});

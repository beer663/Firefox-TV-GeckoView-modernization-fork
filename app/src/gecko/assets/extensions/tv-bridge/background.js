/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

"use strict";

// Runs in accessible frames without reading addresses, cookies or media URLs.
function collectPlayback() {
  const finite = value => Number.isFinite(value) ? value : null;

  return Array.from(document.querySelectorAll("video"))
    .slice(0, 8)
    .map(video => {
      let bufferedAhead = 0;

      for (let index = 0; index < video.buffered.length; index++) {
        if (
          video.currentTime >= video.buffered.start(index) &&
          video.currentTime <= video.buffered.end(index)
        ) {
          bufferedAhead = Math.max(
            bufferedAhead,
            video.buffered.end(index) - video.currentTime
          );
        }
      }

      const quality =
        typeof video.getVideoPlaybackQuality === "function"
          ? video.getVideoPlaybackQuality()
          : null;

      return {
        width: video.videoWidth,
        height: video.videoHeight,
        time: finite(video.currentTime),
        bufferedAhead: finite(bufferedAhead),
        dropped: quality
          ? finite(quality.droppedVideoFrames)
          : null,
        total: quality
          ? finite(quality.totalVideoFrames)
          : null,
        paused: video.paused,
        muted: video.muted,
        volume: finite(video.volume),
        speed: finite(video.playbackRate),
        seeking: video.seeking,
        ended: video.ended,
        ready: video.readyState,
        network: video.networkState,
        fullscreen: !!document.fullscreenElement,
        error: video.error ? video.error.code : 0
      };
    });
}

// Only this extension's own top-level content script can request operations.
browser.runtime.onMessage.addListener((message, sender) => {
  if (
    sender.id !== browser.runtime.id ||
    !sender.tab ||
    sender.frameId !== 0
  ) {
    return undefined;
  }

  if (
    message.action === "eval" &&
    typeof message.code === "string"
  ) {
    return browser.tabs.executeScript(sender.tab.id, {
      code: message.code,
      frameId: 0,
      runAt: "document_start"
    });
  }

  if (message.action === "collectPlayback") {
    return browser.tabs.executeScript(sender.tab.id, {
      code: "(" + collectPlayback.toString() + ")()",
      allFrames: true,
      runAt: "document_idle"
    }).then(frames => frames.slice(0, 16));
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

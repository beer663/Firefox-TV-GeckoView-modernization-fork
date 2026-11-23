/* TV browser video controls. Runs inside Gecko content frames, without a native player. */
(() => {
  "use strict";

  const MESSAGE = "mom-tv-video-context-v1";
  const HELLO = "mom-tv-video-size-v1";
  const frameSizes = new WeakMap();
  const sentFrames = new WeakMap();
  const ntvHost = /(^|\.)ntv\.cx$/i.test(location.hostname);

  let ntvContext = ntvHost;
  let parentClip = null;
  let host = null;
  let button = null;
  let selected = null;
  let queued = false;
  let animation = 0;
  let timer = null;
  let resizeObserver = null;
  let observedVideo = null;
  let lastAnnouncement = "";
  let alive = true;

  const intersect = (a, b) => ({
    left: Math.max(a.left, b.left),
    top: Math.max(a.top, b.top),
    right: Math.min(a.right, b.right),
    bottom: Math.min(a.bottom, b.bottom)
  });

  const viewport = () => {
    const v = window.visualViewport;
    const left = v ? v.offsetLeft : 0;
    const top = v ? v.offsetTop : 0;

    return {
      left,
      top,
      right: left + (v ? v.width : innerWidth),
      bottom: top + (v ? v.height : innerHeight)
    };
  };

  const available = () =>
    parentClip ? intersect(viewport(), parentClip) : viewport();

  const fullscreen = () =>
    document.fullscreenElement || document.mozFullScreenElement;

  const muted = video => video.muted || video.volume === 0;

  function style(node, key, value) {
    if (
      node.style.getPropertyValue(key) !== value ||
      node.style.getPropertyPriority(key) !== "important"
    ) {
      node.style.setProperty(key, value, "important");
    }
  }

  function clipped(node, area) {
    let result = intersect(area, node.getBoundingClientRect());

    for (
      let parent = node.parentElement, depth = 0;
      parent && depth < 32;
      parent = parent.parentElement, depth++
    ) {
      const css = getComputedStyle(parent);

      if (css.display === "none" || css.visibility === "hidden") {
        return {left: 0, top: 0, right: 0, bottom: 0};
      }

      const box = parent.getBoundingClientRect();

      if (/^(hidden|clip|auto|scroll)$/.test(css.overflowX)) {
        result.left = Math.max(result.left, box.left);
        result.right = Math.min(result.right, box.right);
      }

      if (/^(hidden|clip|auto|scroll)$/.test(css.overflowY)) {
        result.top = Math.max(result.top, box.top);
        result.bottom = Math.min(result.bottom, box.bottom);
      }
    }

    return result;
  }

  function bestVideo() {
    let best = null;
    let bestScore = 0;
    const area = available();

    for (const video of document.querySelectorAll("video")) {
      const css = getComputedStyle(video);

      if (
        css.display === "none" ||
        css.visibility === "hidden" ||
        Number(css.opacity) === 0 ||
        !video.isConnected
      ) {
        continue;
      }

      const box = clipped(video, area);
      const width = box.right - box.left;
      const height = box.bottom - box.top;

      if (width < 100 || height < 60) continue;

      const score =
        width * height * (!video.paused && !video.ended ? 1.2 : 1);

      if (score > bestScore) {
        bestScore = score;
        best = {video, box};
      }
    }

    return best;
  }

  function createControls() {
    if (host || !document.documentElement) return;

    host = document.createElement("div");
    host.id = "mom-tv-video-controls";

    style(host, "position", "fixed");
    style(host, "z-index", "2147483647");
    style(host, "display", "none");
    style(host, "pointer-events", "none");
    style(host, "width", "126px");
    style(host, "height", "48px");

    const shadow = host.attachShadow({mode: "closed"});
    const css = document.createElement("style");

    css.textContent = `
      :host { all: initial; }
      button {
        box-sizing:border-box;
        width:126px;
        height:48px;
        border:1px solid #d8dee8;
        border-radius:24px;
        background:#30363d;
        color:white;
        font:600 14px system-ui;
        box-shadow:0 2px 8px #0009;
        cursor:pointer;
        pointer-events:auto;
        touch-action:manipulation;
        white-space:nowrap;
      }
      button:focus-visible {
        outline:3px solid white;
        outline-offset:2px;
      }
      button:active { background:#505965; }
    `;

    button = document.createElement("button");
    button.type = "button";

    shadow.append(css, button);
    document.documentElement.appendChild(host);
  }

  function isControlEvent(event) {
    return (
      host &&
      host.style.display !== "none" &&
      event.composedPath().includes(host)
    );
  }

  function activate(event) {
    if (!event.isTrusted || !isControlEvent(event)) return;

    event.preventDefault();
    event.stopImmediatePropagation();

    const video = selected;
    if (!video || !video.isConnected) return;

    if (muted(video)) {
      video.removeAttribute("muted");
      video.defaultMuted = false;
      video.muted = false;

      if (video.volume === 0) video.volume = 1;

      if (video.paused) {
        const play = video.play();
        if (play && play.catch) play.catch(() => {});
      }

      schedule();
    } else {
      const request =
        video.requestFullscreen || video.mozRequestFullScreen;

      if (request) {
        try {
          const result = request.call(video);

          if (result && result.catch) {
            result.catch(error => {
              console.warn("TV video fullscreen unavailable", error);
              schedule();
            });
          }
        } catch (error) {
          console.warn("TV video fullscreen unavailable", error);
        }
      }
    }
  }

  // Capture our controls before page-level click handlers that can launch ads.
  window.addEventListener("click", activate, true);

  for (const name of [
    "pointerdown",
    "mousedown",
    "touchstart",
    "pointerup",
    "mouseup",
    "touchend"
  ]) {
    window.addEventListener(
      name,
      event => {
        if (event.isTrusted && isControlEvent(event)) {
          event.stopImmediatePropagation();
        }
      },
      {capture: true, passive: true}
    );
  }

  window.addEventListener(
    "keydown",
    event => {
      if (
        (event.key === "Enter" || event.key === " ") &&
        !event.repeat
      ) {
        activate(event);
      }
    },
    true
  );

  // Ordinary target=_blank links and forms stay usable in the current tab.
  // Native Gecko delegates reject every actual request for another window.
  window.addEventListener(
    "click",
    event => {
      if (!event.isTrusted || event.defaultPrevented) return;

      const anchor = event.composedPath().find(
        node => node instanceof HTMLAnchorElement
      );

      if (!anchor) return;

      const target =
        anchor.target || document.querySelector("base[target]")?.target;

      if (target && target.toLowerCase() === "_blank") {
        anchor.target = "_self";
      }
    },
    true
  );

  document.addEventListener(
    "submit",
    event => {
      const form = event.target;
      if (!(form instanceof HTMLFormElement)) return;

      const submitter = event.submitter;
      const target =
        submitter?.formTarget ||
        form.target ||
        document.querySelector("base[target]")?.target;

      if (target && target.toLowerCase() === "_blank") {
        form.target = "_self";

        if (submitter && "formTarget" in submitter) {
          submitter.formTarget = "_self";
        }
      }
    },
    true
  );

  function fitNtv() {
    const root = document.documentElement;
    if (!root) return;

    if (!document.getElementById("mom-tv-video-style")) {
      const css = document.createElement("style");
      css.id = "mom-tv-video-style";

      css.textContent = `
        video:fullscreen {
          width:100% !important;
          height:100% !important;
          max-width:none !important;
          max-height:none !important;
          object-fit:contain !important;
          background:black;
        }

        html[data-mom-tv-ntv] {
          height:auto !important;
          min-height:100% !important;
          overflow-y:auto !important;
        }

        html[data-mom-tv-ntv] body {
          height:auto !important;
          max-height:none !important;
          overflow-y:visible !important;
        }

        html[data-mom-tv-ntv] .main-layout:has(.watch-page-wrapper),
        html[data-mom-tv-ntv] .main-layout:has(.matches-page-wrapper) {
          margin-top:var(--navbar-height,0px) !important;
          height:auto !important;
          min-height:0 !important;
          overflow:visible !important;
          padding-bottom:calc(var(--mobile-nav-clearance,0px) + 12px) !important;
        }

        html[data-mom-tv-ntv] .watch-page-wrapper,
        html[data-mom-tv-ntv] .watch-page-container,
        html[data-mom-tv-ntv] .matches-page-wrapper,
        html[data-mom-tv-ntv] .matches-page-container,
        html[data-mom-tv-ntv] .matches-content-wrapper {
          position:relative !important;
          inset:auto !important;
          height:auto !important;
          max-height:none !important;
          min-height:0 !important;
          overflow:visible !important;
        }

        @media (max-width:1261px) {
          html[data-mom-tv-ntv] .main-layout:has(.watch-page-wrapper),
          html[data-mom-tv-ntv] .main-layout:has(.matches-page-wrapper) {
            margin-top:0 !important;
          }
        }

        html[data-mom-tv-ntv].layout-nav-bottom .main-layout {
          margin-top:0 !important;
        }

        html[data-mom-tv-ntv] .watch-player-wrapper {
          position:relative !important;
          inset:auto !important;
          min-height:0 !important;
          height:min(56.25vw,var(--mom-tv-player-max)) !important;
          max-height:var(--mom-tv-player-max) !important;
          overflow:hidden !important;
        }

        html[data-mom-tv-ntv] .watch-player-wrapper iframe,
        html[data-mom-tv-ntv] .watch-player-wrapper video {
          width:100% !important;
          height:100% !important;
          max-height:100% !important;
          object-fit:contain !important;
        }
      `;

      root.appendChild(css);
    }

    const fit =
      ntvHost &&
      !!document.querySelector(".watch-page-wrapper,.matches-page-wrapper") &&
      !fullscreen();

    if (fit) {
      const area = viewport();

      style(
        root,
        "--mom-tv-player-max",
        Math.max(120, Math.floor((area.bottom - area.top) * 0.8)) + "px"
      );

      if (!root.hasAttribute("data-mom-tv-ntv")) {
        root.setAttribute("data-mom-tv-ntv", "");
      }
    } else {
      root.removeAttribute("data-mom-tv-ntv");
    }

    if (ntvContext || fullscreen()) {
      for (const video of document.querySelectorAll("video")) {
        style(video, "object-fit", "contain");
      }
    }
  }

  function tellParent() {
    if (window === window.top) return;

    const signature = innerWidth + ":" + innerHeight;
    if (lastAnnouncement === signature) return;

    lastAnnouncement = signature;

    window.parent.postMessage(
      {marker: HELLO, width: innerWidth, height: innerHeight},
      "*"
    );
  }

  function updateFrames() {
    for (const frame of document.querySelectorAll("iframe,frame")) {
      const box = frame.getBoundingClientRect();
      const size = frameSizes.get(frame) || {
        width: frame.clientWidth,
        height: frame.clientHeight
      };

      if (!frame.contentWindow || !size.width || !size.height) continue;

      const sx = box.width / Math.max(1, frame.offsetWidth);
      const sy = box.height / Math.max(1, frame.offsetHeight);
      const left = box.left + frame.clientLeft * sx;
      const top = box.top + frame.clientTop * sy;
      const width = frame.clientWidth * sx;
      const height = frame.clientHeight * sy;
      const area = clipped(frame, available());

      let clip = {left: 0, top: 0, right: 0, bottom: 0};

      if (
        width > 0 &&
        height > 0 &&
        area.right > area.left &&
        area.bottom > area.top
      ) {
        clip = {
          left: Math.max(0, (area.left - left) * size.width / width),
          top: Math.max(0, (area.top - top) * size.height / height),
          right: Math.min(
            size.width,
            (area.right - left) * size.width / width
          ),
          bottom: Math.min(
            size.height,
            (area.bottom - top) * size.height / height
          )
        };
      }

      const data = {marker: MESSAGE, clip, ntv: ntvContext};
      const signature = JSON.stringify(data);

      if (sentFrames.get(frame) === signature) continue;

      sentFrames.set(frame, signature);
      frame.contentWindow.postMessage(data, "*");
    }
  }

  window.addEventListener("message", event => {
    const data = event.data;
    if (!data || typeof data !== "object") return;

    if (
      data.marker === HELLO &&
      Number.isFinite(data.width) &&
      Number.isFinite(data.height) &&
      data.width > 0 &&
      data.height > 0
    ) {
      const frame = [...document.querySelectorAll("iframe,frame")].find(
        item => item.contentWindow === event.source
      );

      if (frame) {
        frameSizes.set(frame, {
          width: data.width,
          height: data.height
        });

        sentFrames.delete(frame);
        schedule();
      }
    } else if (
      data.marker === MESSAGE &&
      window !== window.top &&
      event.source === window.parent
    ) {
      const c = data.clip;

      if (
        !c ||
        ![c.left, c.top, c.right, c.bottom].every(Number.isFinite) ||
        c.right < c.left ||
        c.bottom < c.top
      ) {
        return;
      }

      parentClip = c;
      ntvContext = ntvHost || data.ntv === true;
      schedule();
    }
  });

  function refresh() {
    queued = false;
    animation = 0;

    if (!alive || document.hidden) {
      if (host) style(host, "display", "none");
      return;
    }

    createControls();
    fitNtv();
    tellParent();
    updateFrames();

    const candidate = bestVideo();
    selected = candidate?.video || null;

    if (resizeObserver && selected !== observedVideo) {
      if (observedVideo) resizeObserver.unobserve(observedVideo);
      observedVideo = selected;
      if (selected) resizeObserver.observe(selected);
    }

    if (host) {
      if (
        !candidate ||
        fullscreen() ||
        candidate.box.right - candidate.box.left < 142 ||
        candidate.box.bottom - candidate.box.top < 64
      ) {
        style(host, "display", "none");
      } else {
        if (!host.isConnected) {
          document.documentElement.appendChild(host);
        }

        const area = candidate.box;

        style(
          host,
          "left",
          Math.max(
            area.left + 8,
            Math.min(area.right - 134, area.left + 8)
          ) + "px"
        );

        style(
          host,
          "top",
          Math.max(area.top + 8, area.bottom - 56) + "px"
        );

        style(host, "display", "block");

        const text = muted(selected) ? "Unmute" : "Full screen";

        if (button.textContent !== text) button.textContent = text;
        button.setAttribute("aria-label", text + " video");
      }
    }

    if (timer) clearTimeout(timer);
    timer = null;

    if (selected || document.querySelector("iframe,frame")) {
      timer = setTimeout(schedule, 1200);
    }
  }

  function schedule() {
    if (queued || !alive || document.hidden) return;

    queued = true;
    animation = requestAnimationFrame(refresh);
  }

  const observer = new MutationObserver(schedule);
  observer.observe(document, {childList: true, subtree: true});

  if (typeof ResizeObserver === "function") {
    resizeObserver = new ResizeObserver(schedule);
  }

  for (const name of [
    "loadedmetadata",
    "volumechange",
    "play",
    "playing",
    "pause",
    "emptied",
    "fullscreenchange"
  ]) {
    document.addEventListener(name, schedule, true);
  }

  for (const name of ["resize", "scroll"]) {
    window.addEventListener(
      name,
      schedule,
      {capture: true, passive: true}
    );
  }

  if (window.visualViewport) {
    visualViewport.addEventListener("resize", schedule);
    visualViewport.addEventListener("scroll", schedule);
  }

  document.addEventListener("visibilitychange", () => {
    if (timer) clearTimeout(timer);
    timer = null;

    if (document.hidden) {
      cancelAnimationFrame(animation);
      animation = 0;
      queued = false;

      if (host) style(host, "display", "none");

      for (const media of document.querySelectorAll("video,audio")) {
        media.pause();
      }
    } else {
      schedule();
    }
  });

  window.addEventListener("pagehide", () => {
    if (timer) clearTimeout(timer);
    timer = null;
    alive = false;

    cancelAnimationFrame(animation);
    animation = 0;
    queued = false;

    if (host) style(host, "display", "none");
  });

  window.addEventListener("pageshow", () => {
    alive = true;
    lastAnnouncement = "";
    schedule();
  });

  schedule();
})();

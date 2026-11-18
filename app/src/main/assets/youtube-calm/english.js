// English videos on YouTube. YouTube has no language filter for search, so this does two things:
// 1. Sets YouTube's own language and region preference (the PREF cookie) to English and the US. YouTube then ranks
//    English videos first and shows titles as written; in Korean it machine-translates English titles into Korean.
// 2. On search results, hides videos whose title is at least a third Korean, Japanese, Chinese or another non-Latin
//    script, or whose channel name has any such letter, and channels named that way.
"use strict";

(() => {
  const WANTED = [["hl", "en"], ["gl", "US"]];
  const RELOADED = "air-english-reloaded";

  function readPrefs() {
    const match = document.cookie.match(/(?:^|;\s*)PREF=([^;]*)/);
    const prefs = new Map();
    for (const part of match ? match[1].split("&") : []) {
      if (!part) continue;
      const equals = part.indexOf("=");
      prefs.set(equals < 0 ? part : part.slice(0, equals), equals < 0 ? "" : part.slice(equals + 1));
    }
    return prefs;
  }

  const isEnglishPrefs = (prefs) => WANTED.every(([key, value]) => prefs.get(key) === value);

  // This page was already requested in the old language, so it loads once more, unless this tab just did: a cookie
  // the server keeps resetting must not cause a reload loop, and without storage for that guard it never reloads.
  function reloadOnce() {
    try {
      if (Date.now() - (Number(sessionStorage.getItem(RELOADED)) || 0) < 30000) return;
      sessionStorage.setItem(RELOADED, String(Date.now()));
    } catch (e) {
      return;
    }
    location.reload();
  }

  const prefs = readPrefs();
  if (!isEnglishPrefs(prefs)) {
    for (const [key, value] of WANTED) prefs.set(key, value);
    const value = Array.from(prefs, ([key, part]) => (part === "" ? key : key + "=" + part)).join("&");
    document.cookie = "PREF=" + value + "; domain=.youtube.com; path=/; max-age=63072000; secure; samesite=lax";
    if (isEnglishPrefs(readPrefs())) reloadOnce();
  }

  // Letters, and the vowel signs of scripts such as Hindi's, of a script other than Latin. Common covers digits,
  // punctuation, emoji and styled letters such as 𝕭; Inherited covers accents.
  const FOREIGN = /(?![\p{Script=Latin}\p{Script=Common}\p{Script=Inherited}])[\p{L}\p{M}]/gu;
  const LETTER = /[\p{L}\p{M}]/gu;
  const ITEMS = [
    "ytm-video-with-context-renderer", "ytm-compact-video-renderer", "ytm-compact-channel-renderer",
    "ytm-compact-playlist-renderer", "yt-lockup-view-model", "ytd-video-renderer", "ytd-channel-renderer",
    "ytd-playlist-renderer",
  ].join(", ");
  // Only results inside the search page: a channel page or a playlist kept in the page must never lose its videos.
  const RESULTS = `:is(ytm-search, ytd-search) :is(${ITEMS})`;
  const HIDDEN = "air-not-english";
  const NONE_LEFT = "air-no-english";

  const count = (text, pattern) => (text.match(pattern) || []).length;

  function foreignShare(text) {
    const letters = count(text, LETTER);
    return letters ? count(text, FOREIGN) / letters : 0;
  }

  const textOf = (item, selector) => {
    const node = item.querySelector(selector);
    return node ? node.textContent.trim() : "";
  };

  function isEnglish(item) {
    const title = textOf(item, "h3, h4, #video-title");
    const channel = textOf(item, ".ytmBadgeAndBylineRendererItemByline, ytd-channel-name #text");
    if (!title && !channel) return true;
    return foreignShare(title) < 1 / 3 && foreignShare(channel) === 0;
  }

  function filterResults() {
    const page = document.documentElement;
    if (!page) return;
    if (location.pathname !== "/results") {
      page.removeAttribute(NONE_LEFT);
      return;
    }
    let shown = 0;
    let checked = 0;
    for (const item of document.querySelectorAll(RESULTS)) {
      if (item.parentElement && item.parentElement.closest(ITEMS)) continue; // a part of a larger result
      checked++;
      if (isEnglish(item)) {
        item.removeAttribute(HIDDEN);
        shown++;
      } else item.setAttribute(HIDDEN, "");
    }
    // With nothing left to show, YouTube would keep loading further pages into view; stop it and say why.
    if (checked >= 10 && shown === 0) page.setAttribute(NONE_LEFT, "");
    else page.removeAttribute(NONE_LEFT);
  }

  let queued = false;
  const schedule = () => {
    if (queued) return;
    queued = true;
    setTimeout(() => { queued = false; filterResults(); }, 120);
  };
  new MutationObserver(schedule).observe(document, { childList: true, subtree: true, characterData: true });
  schedule();
})();

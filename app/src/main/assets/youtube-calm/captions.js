// Turns captions on for every YouTube video and shows them in Korean: a Korean track when the video
// has one, otherwise YouTube's own auto-translation of the video's captions. Once the player reports
// Korean captions for a video it stops, so turning captions off or picking another language sticks.
"use strict";

(() => {
  const TARGET = { languageCode: "ko", languageName: "한국어" };
  const ATTEMPTS = 40; // about 20 seconds of playing video (ads excluded) to get Korean captions showing
  let doneFor = null;
  let triesFor = null;
  let tries = 0;

  // Firefox content scripts see the page's objects through an Xray wrapper; this reaches the player's methods.
  const page = (node) => (node && node.wrappedJSObject) || node;
  const toPage = (value) => (typeof cloneInto === "function" ? cloneInto(value, window) : value);

  const isKorean = (track) => /^ko(\b|-|$)/i.test(String((track && track.languageCode) || ""));
  const isEnglish = (track) => /^en(\b|-|$)/i.test(String(track.languageCode || ""));
  const isAuto = (track) => track.kind === "asr";

  function findPlayer() {
    return document.getElementById("movie_player") || document.querySelector(".html5-video-player");
  }

  function plain(track) {
    const copy = {};
    for (const key of ["languageCode", "languageName", "kind", "name", "displayName", "id", "vss_id", "is_translateable", "is_default", "is_servable"]) {
      if (track[key] !== undefined) copy[key] = typeof track[key] === "object" ? JSON.parse(JSON.stringify(track[key])) : track[key];
    }
    return copy;
  }

  /** The player's own list. Auto-generated (asr) tracks are left out unless asked for, and many videos only have those. */
  function playerTracks(player) {
    for (const options of [{ includeAsr: true }, undefined]) {
      try {
        const list = options ? player.getOption("captions", "tracklist", toPage(options)) : player.getOption("captions", "tracklist");
        const tracks = list ? Array.from(list, (t) => page(t)) : [];
        if (tracks.length) return tracks;
      } catch (e) { /* try the next form */ }
    }
    return [];
  }

  /** The video's caption list as YouTube sent it, for players that have not filled their own list yet. */
  function responseTracks(player) {
    try {
      if (typeof player.getPlayerResponse !== "function") return [];
      const response = page(player.getPlayerResponse());
      const renderer = response && response.captions && page(response.captions).playerCaptionsTracklistRenderer;
      const list = renderer && page(renderer).captionTracks;
      return list ? Array.from(list, (raw) => {
        const t = page(raw);
        const track = { languageCode: t.languageCode, vss_id: t.vssId, is_translateable: t.isTranslatable };
        if (t.kind) track.kind = t.kind;
        return track;
      }) : [];
    } catch (e) {
      return [];
    }
  }

  /** Korean as written by people first, then any Korean, then a written source to translate (English first), then auto captions. */
  function choose(tracks) {
    const korean = tracks.find((t) => isKorean(t) && !isAuto(t)) || tracks.find(isKorean);
    if (korean) return plain(korean);
    const rank = (t) => (isAuto(t) ? 2 : 0) + (isEnglish(t) ? 0 : 1);
    const source = tracks
      .filter((t) => t.is_translateable !== false)
      .sort((a, b) => rank(a) - rank(b))[0];
    if (!source) return null;
    return Object.assign(plain(source), { translationLanguage: TARGET });
  }

  /** True once the player is showing Korean captions, written or translated. */
  function showingKorean(player) {
    try {
      const current = page(player.getOption("captions", "track"));
      if (!current) return false;
      return isKorean(current) || isKorean(page(current.translationLanguage));
    } catch (e) {
      return false;
    }
  }

  function tick() {
    const element = findPlayer();
    const player = page(element);
    if (!player || typeof player.getVideoData !== "function" || typeof player.setOption !== "function") return;
    // An ad plays in the same player and its captions are replaced when the video starts.
    if (element.classList && (element.classList.contains("ad-showing") || element.classList.contains("ad-interrupting"))) return;
    let videoId;
    try { videoId = player.getVideoData().video_id; } catch (e) { return; }
    if (!videoId || videoId === doneFor) return;
    if (triesFor !== videoId) { triesFor = videoId; tries = 0; }
    if (++tries > ATTEMPTS) { doneFor = videoId; return; } // no captions, or the player would not take them
    if (showingKorean(player)) { doneFor = videoId; return; }
    try {
      if (typeof player.loadModule === "function") player.loadModule("captions");
      let tracks = playerTracks(player);
      if (!tracks.length) tracks = responseTracks(player);
      if (!tracks.length) return;
      const track = choose(tracks);
      if (!track) { doneFor = videoId; return; }
      player.setOption("captions", "track", toPage(track));
      // The next tick checks that the player took it, and tries again if a video start or the player reset it.
    } catch (e) {
      // The caption module is still loading; try again on the next tick.
    }
  }

  setInterval(tick, 500);
})();

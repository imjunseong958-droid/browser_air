// Turns captions on for every YouTube video and shows them in Korean: a Korean track when the video
// has one, otherwise YouTube's own auto-translation of the video's captions. It acts once per video,
// so turning captions off or picking another language in the player sticks for that video.
"use strict";

(() => {
  const TARGET = { languageCode: "ko", languageName: "한국어" };
  const ATTEMPTS = 20; // about 10 seconds for the player and its caption list to appear
  let doneFor = null;
  let triesFor = null;
  let tries = 0;

  // Firefox content scripts see the page's objects through an Xray wrapper; this reaches the player's methods.
  const page = (node) => (node && node.wrappedJSObject) || node;
  const toPage = (value) => (typeof cloneInto === "function" ? cloneInto(value, window) : value);

  const isKorean = (track) => /^ko(\b|-|$)/i.test(String(track.languageCode || ""));
  const isEnglish = (track) => /^en(\b|-|$)/i.test(String(track.languageCode || ""));
  const isAuto = (track) => track.kind === "asr";

  function plain(track) {
    const copy = {};
    for (const key of ["languageCode", "languageName", "kind", "name", "displayName", "id", "vss_id", "is_translateable", "is_default", "is_servable"]) {
      if (track[key] !== undefined) copy[key] = typeof track[key] === "object" ? JSON.parse(JSON.stringify(track[key])) : track[key];
    }
    return copy;
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

  function tick() {
    const player = page(document.getElementById("movie_player"));
    if (!player || typeof player.getVideoData !== "function" || typeof player.setOption !== "function") return;
    let videoId;
    try { videoId = player.getVideoData().video_id; } catch (e) { return; }
    if (!videoId || videoId === doneFor) return;
    if (triesFor !== videoId) { triesFor = videoId; tries = 0; }
    if (++tries > ATTEMPTS) { doneFor = videoId; return; } // no captions at all
    try {
      player.loadModule("captions");
      const list = player.getOption("captions", "tracklist");
      const tracks = list ? Array.from(list, (t) => page(t)) : [];
      if (!tracks.length) return;
      const track = choose(tracks);
      if (!track) { doneFor = videoId; return; }
      player.setOption("captions", "track", toPage(track));
      doneFor = videoId;
    } catch (e) {
      // The caption module is still loading; try again on the next tick.
    }
  }

  setInterval(tick, 500);
})();

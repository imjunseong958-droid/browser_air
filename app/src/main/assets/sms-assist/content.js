(() => {
  if (window !== window.top || !window.isSecureContext) return;
  let port;
  let documentId;
  let requestId;
  let fields = new Map();
  let observer;
  let scheduled;
  let lastState;

  function reportState(type = "state") {
    if (!port) return;
    const emptyTarget = AirSmsFields.scan(document).targets.some(target => target.identified);
    const state = `${location.href}|${AirSmsFields.hasTarget(document)}|${emptyTarget}|${document.visibilityState}`;
    if (type === "state" && state === lastState) return;
    lastState = state;
    port.postMessage({ type, document: documentId, href: location.href,
      emptyTarget, hasTarget: document.visibilityState === "visible" && AirSmsFields.hasTarget(document) });
  }
  function scheduleState() {
    clearTimeout(scheduled);
    scheduled = setTimeout(() => reportState(), 50);
  }
  function connect() {
    documentId = crypto.randomUUID();
    requestId = null;
    fields.clear();
    lastState = null;
    port = browser.runtime.connectNative("air_sms");
    const connected = port;
    connected.onDisconnect.addListener(() => {
      if (port === connected) { port = null; fields.clear(); requestId = null; observer?.disconnect(); clearTimeout(scheduled); }
    });
    connected.onMessage.addListener(async message => {
      if (!message || message.document !== documentId || message.href !== location.href) return;
      const reply = { request: message.request, document: documentId, href: location.href };
      const bound = () => port === connected && message.request === requestId
        && message.document === documentId && message.href === location.href;
      const current = () => bound() && document.visibilityState === "visible";
      if (message.type === "cancel") {
        if (message.request === requestId) { requestId = null; fields.clear(); }
        return;
      }
      if (message.type === "scan") {
        requestId = message.request;
        let result = AirSmsFields.scan(document, message.code);
        // Some sites mount their OTP widget only after the SMS has been requested.
        for (let attempt = 0; (document.visibilityState !== "visible" || !result.targets.some(target => target.identified))
          && attempt < 20 && bound(); attempt++) {
          await new Promise(resolve => setTimeout(resolve, 100));
          result = AirSmsFields.scan(document, message.code);
        }
        if (!current()) return;
        fields.clear();
        reply.type = "fields";
        reply.fields = result.targets.map((target, index) => {
          const id = String(index);
          fields.set(id, target);
          if (target === result.automatic) reply.automatic = id;
          return { id, label: AirSmsFields.label(target) };
        });
      } else if (message.type === "fill") {
        reply.type = "filled";
        Object.assign(reply, current() ? await AirSmsFields.complete(fields.get(message.field), message.code, current)
          : { success: false, submitted: false });
        if (!current()) return;
        fields.clear(); requestId = null;
      } else return;
      connected.postMessage(reply);
    });
    observer = new MutationObserver(scheduleState);
    observer.observe(document.documentElement, { subtree: true, childList: true, attributes: true,
      attributeFilter: ["autocomplete", "disabled", "readonly", "hidden", "style", "class", "maxlength", "id", "name", "aria-label", "placeholder"] });
    reportState("ready");
  }
  connect();
  document.addEventListener("input", scheduleState, true);
  document.addEventListener("change", scheduleState, true);
  document.addEventListener("visibilitychange", scheduleState);
  window.addEventListener("popstate", scheduleState);
  window.addEventListener("hashchange", scheduleState);
  window.addEventListener("pagehide", () => {
    const connected = port;
    port = null;
    requestId = null;
    observer?.disconnect();
    clearTimeout(scheduled);
    if (connected) connected.disconnect();
    fields.clear();
  });
  window.addEventListener("pageshow", event => { if (event.persisted && !port) connect(); });
})();

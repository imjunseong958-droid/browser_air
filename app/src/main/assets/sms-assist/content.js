(() => {
  if (window !== window.top || !window.isSecureContext) return;
  let port;
  let documentId;
  let requestId;
  let fields = new Map();

  function connect() {
    documentId = crypto.randomUUID();
    requestId = null;
    fields.clear();
    port = browser.runtime.connectNative("air_sms");
    const connected = port;
    connected.onDisconnect.addListener(() => { if (port === connected) { port = null; fields.clear(); } });
    connected.onMessage.addListener(message => {
      if (!message || message.document !== documentId || message.href !== location.href) return;
      const reply = { request: message.request, document: documentId, href: location.href };
      if (message.type === "cancel") {
        if (message.request === requestId) { requestId = null; fields.clear(); }
        return;
      }
      if (message.type === "scan") {
        requestId = message.request;
        const result = AirSmsFields.scan(document);
        fields.clear();
        reply.type = "fields";
        reply.fields = result.inputs.map((input, index) => {
          const id = String(index);
          fields.set(id, input);
          if (input === result.automatic) reply.automatic = id;
          return { id, label: AirSmsFields.label(input) };
        });
      } else if (message.type === "fill") {
        reply.type = "filled";
        reply.success = message.request === requestId && document.visibilityState === "visible"
          && AirSmsFields.fill(fields.get(message.field), message.code);
        if (message.request === requestId) { fields.clear(); requestId = null; }
      } else return;
      connected.postMessage(reply);
    });
    connected.postMessage({ type: "ready", document: documentId });
  }

  connect();
  window.addEventListener("pagehide", () => {
    const connected = port;
    port = null;
    requestId = null;
    if (connected) connected.disconnect();
    fields.clear();
  });
  window.addEventListener("pageshow", event => { if (event.persisted && !port) connect(); });
})();

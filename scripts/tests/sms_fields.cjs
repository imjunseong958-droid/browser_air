const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { JSDOM } = require("jsdom");
const assets = path.resolve(__dirname, "../../app/src/main/assets/sms-assist");

function fixture(html) {
  const dom = new JSDOM(html, { url: "https://example.com/verify", runScripts: "outside-only", pretendToBeVisual: true });
  const { window } = dom;
  // jsdom has no layout engine. Simulate boxes while leaving style/DOM semantics intact.
  window.HTMLInputElement.prototype.getClientRects = function () {
    return this.hidden ? [] : [{ width: 180, height: 40 }];
  };
  window.eval(fs.readFileSync(path.join(assets, "fields.js"), "utf8"));
  return { window, document: window.document, fields: window.AirSmsFields };
}

test("one standard OTP field is selected independently of a phone field", () => {
  const { document, fields } = fixture('<input type="tel" id="phone"><input autocomplete="one-time-code" id="otp">');
  const scan = fields.scan(document);
  assert.equal(scan.automatic.id, "otp");
  assert.deepEqual(Array.from(scan.inputs, input => input.id), ["otp", "phone"]);
});

test("multiple OTP fields and unmarked numeric inputs require explicit selection", () => {
  for (const html of ['<input autocomplete="one-time-code"><input autocomplete="one-time-code">', '<input inputmode="numeric">']) {
    const { document, fields } = fixture(html);
    assert.equal(fields.scan(document).automatic, null);
  }
});

test("hidden, disabled, read-only, inert and split inputs are excluded", () => {
  const { document, fields } = fixture('<input type="hidden"><input disabled><input readonly><div inert><input></div>'
    + '<input hidden><input style="display:none"><input maxlength="1"><input maxlength="3"><input type="password">'
    + '<div style="opacity:0"><input></div><input id="valid">');
  assert.deepEqual(Array.from(fields.scan(document).inputs, input => input.id), ["valid"]);
});

test("existing user input is never overwritten", () => {
  const { document, fields } = fixture('<input autocomplete="one-time-code" value="12" id="otp">');
  const input = document.getElementById("otp");
  assert.equal(fields.fill(input, "123456"), false);
  assert.equal(input.value, "12");
});

test("fill preserves leading zeroes and emits form events without submitting", () => {
  const { document, fields } = fixture('<form><input id="otp" autocomplete="one-time-code"><button>Verify</button></form>');
  const input = document.getElementById("otp");
  const events = [];
  for (const type of ["input", "change", "submit"]) document.addEventListener(type, () => events.push(type));
  assert.equal(fields.fill(input, "001234"), true);
  assert.equal(input.value, "001234");
  assert.deepEqual(events, ["input", "change"]);
});

test("removed fields, short fields, invalid codes and alphanumeric number inputs fail", () => {
  const { document, fields } = fixture('<input maxlength="4" id="short"><input type="number" id="number"><input id="removed">');
  const removed = document.getElementById("removed");
  removed.remove();
  assert.equal(fields.fill(removed, "123456"), false);
  assert.equal(fields.fill(document.getElementById("short"), "123456"), false);
  assert.equal(fields.fill(document.getElementById("number"), "AB1234"), false);
  assert.equal(fields.fill(document.getElementById("number"), "password"), false);
  assert.equal(fields.fill(undefined, "123456"), false);
});

test("field labels are bounded and stripped of bidirectional control characters", () => {
  const { document, fields } = fixture('<label for="otp">인증\u202e번호 ' + "x".repeat(100) + '</label><input id="otp">');
  const label = fields.label(document.getElementById("otp"));
  assert.equal(label.includes("\u202e"), false);
  assert.ok(label.length <= 80);
});

test("the bridge rejects a wrong document or URL and reconnects after back navigation", () => {
  const { window, document } = fixture('<input id="otp" autocomplete="one-time-code">');
  window.isSecureContext = true;
  const ports = [];
  window.browser = { runtime: { connectNative() {
    const messages = [], listeners = [], disconnected = [];
    const port = { messages, onMessage: { addListener: fn => listeners.push(fn) },
      onDisconnect: { addListener: fn => disconnected.push(fn) }, postMessage: data => messages.push(data),
      disconnect: () => disconnected.forEach(fn => fn()), deliver: data => listeners.forEach(fn => fn(data)) };
    ports.push(port);
    return port;
  } } };
  window.eval(fs.readFileSync(path.join(assets, "content.js"), "utf8"));
  const port = ports[0], ready = port.messages[0];
  const base = { document: ready.document, href: window.location.href, request: "1" };
  port.deliver({ ...base, type: "scan", document: "old-document" });
  port.deliver({ ...base, type: "scan", href: "https://other.example/verify" });
  assert.equal(port.messages.length, 1);
  port.deliver({ ...base, type: "scan" });
  assert.equal(port.messages[1].automatic, "0");
  port.deliver({ ...base, request: "old-request", type: "fill", field: "0", code: "987654" });
  assert.equal(document.getElementById("otp").value, "");
  port.deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.equal(document.getElementById("otp").value, "001234");
  assert.equal(port.messages.at(-1).success, true);
  assert.equal(JSON.stringify(port.messages).includes("001234"), false);
  window.dispatchEvent(new window.PageTransitionEvent("pagehide"));
  window.dispatchEvent(new window.PageTransitionEvent("pageshow", { persisted: true }));
  assert.equal(ports.length, 2);
  assert.notEqual(ports[1].messages[0].document, ready.document);
  document.getElementById("otp").value = "";
  const next = ports[1];
  const second = { ...base, document: next.messages[0].document, request: "2" };
  next.deliver({ ...second, type: "scan" });
  next.deliver({ ...second, type: "cancel" });
  next.deliver({ ...second, type: "fill", field: "0", code: "123456" });
  assert.equal(document.getElementById("otp").value, "");
});

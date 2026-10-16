const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { JSDOM } = require("jsdom");
const assets = path.resolve(__dirname, "../../app/src/main/assets/sms-assist");

function fixture(html) {
  const dom = new JSDOM(html, { url: "https://example.com/verify", runScripts: "outside-only", pretendToBeVisual: true });
  const { window } = dom;
  window.Element.prototype.getClientRects = function () { return this.hidden ? [] : [{ width: 180, height: 40 }]; };
  window.eval(fs.readFileSync(path.join(assets, "fields.js"), "utf8"));
  return { window, document: window.document, fields: window.AirSmsFields };
}
function target(document, fields, code) { return fields.scan(document, code).automatic; }
const tick = (ms = 150) => new Promise(resolve => setTimeout(resolve, ms));

test("standard and labelled OTP fields are selected independently of phone inputs", () => {
  for (const attributes of ['autocomplete="one-time-code"', 'inputmode="numeric"', 'name="verificationCode"']) {
    const { document, fields } = fixture(`<label for="phone">전화번호</label><input type="tel" id="phone"><label for="otp">인증번호</label><input id="otp" ${attributes}>`);
    assert.equal(target(document, fields).inputs[0].id, "otp");
  }
});

test("numeric OTP fields with a verification form are inferred without choosing a field", () => {
  const { document, fields } = fixture('<form><input name="phone" type="tel"><input id="digits" inputmode="numeric" maxlength="6"><button>인증하기</button></form>');
  assert.equal(target(document, fields).inputs[0].id, "digits");
});

test("ambiguous forms and ordinary numeric/phone/coupon fields are not auto-selected", () => {
  for (const html of ['<input autocomplete="one-time-code"><input autocomplete="one-time-code">', '<input inputmode="numeric">',
    '<form><input id="phone" type="tel" maxlength="10"><button>Verify</button></form>', '<input id="couponCode" maxlength="6">']) {
    const { document, fields } = fixture(html);
    assert.equal(target(document, fields), null);
  }
});

test("hidden, disabled, read-only and inert inputs are excluded from filling", () => {
  const { document, fields } = fixture('<input type="hidden"><input disabled><input readonly><div inert><input></div>'
    + '<input hidden><input style="display:none"><input maxlength="3"><input type="password">'
    + '<div style="opacity:0"><input></div><input id="valid">');
  assert.deepEqual(Array.from(fields.scan(document).targets, item => item.inputs[0].id), ["valid"]);
});

test("a disabled OTP widget arms reception before the site enables it", () => {
  const { document, fields } = fixture('<input autocomplete="one-time-code" disabled>');
  assert.equal(fields.hasTarget(document), true);
  assert.equal(target(document, fields), null);
});

test("existing user input is never overwritten", () => {
  const { document, fields } = fixture('<input autocomplete="one-time-code" value="12" id="otp">');
  assert.equal(fields.fill({ inputs: [document.getElementById("otp")] }, "123456"), false);
  assert.equal(document.getElementById("otp").value, "12");
});

test("fill preserves leading zeroes and emits controlled-form events", () => {
  const { document, fields } = fixture('<input autocomplete="one-time-code">');
  const events = [];
  for (const type of ["input", "change"]) document.addEventListener(type, () => events.push(type));
  assert.equal(fields.fill(target(document, fields), "001234"), true);
  assert.equal(document.querySelector("input").value, "001234");
  assert.deepEqual(events, ["input", "change"]);
});

test("a split OTP widget is filled together including nested digit wrappers", () => {
  const { document, fields } = fixture('<form><div aria-label="인증번호">'
    + Array.from({ length: 6 }, (_, i) => `<span><input maxlength="1" inputmode="numeric" ${i === 0 ? 'autocomplete="one-time-code"' : ''}></span>`).join("") + '</div><button>Verify</button></form>');
  const values = [];
  document.querySelector("input").addEventListener("input", () => values.push([...document.querySelectorAll("input")].map(input => input.value).join("")));
  assert.equal(fields.fill(target(document, fields, "001234"), "001234"), true);
  assert.equal(values[0], "001234");
});

test("split widgets with wrong lengths and partly edited digits are not filled", () => {
  const { document, fields } = fixture('<div aria-label="OTP"><input maxlength="1" value="1"><input maxlength="1"><input maxlength="1"><input maxlength="1"></div>');
  assert.equal(target(document, fields, "123456"), null);
  assert.equal(fields.fill({ inputs: [...document.querySelectorAll("input")] }, "1234"), false);
});

test("partly filled or disabled split widgets cannot be mistaken for a shorter OTP", () => {
  for (const attribute of ['value="1"', 'disabled', 'readonly']) {
    const { document, fields } = fixture('<div aria-label="OTP">'
      + `<input maxlength="1" ${attribute}>` + '<input maxlength="1">'.repeat(5) + '</div>');
    assert.equal(target(document, fields, "12345"), null);
    assert.equal(target(document, fields, "123456"), null);
  }
});

test("removed controls, short fields, invalid codes and alphanumeric number inputs fail", () => {
  const { document, fields } = fixture('<input maxlength="4" id="short"><input type="number" id="number"><input id="removed">');
  const removed = document.getElementById("removed");
  removed.remove();
  for (const [input, code] of [[removed, "123456"], [document.getElementById("short"), "123456"], [document.getElementById("number"), "AB1234"], [document.getElementById("number"), "password"]]) {
    assert.equal(fields.fill({ inputs: [input] }, code), false);
  }
  assert.equal(fields.fill(undefined, "123456"), false);
});

test("OTP completion submits the verification form once and ignores resend", async () => {
  const { document, fields } = fixture('<form><input autocomplete="one-time-code"><button type="button">Resend code</button><button>Verify</button></form>');
  let submitted = 0, resent = 0;
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); submitted++; });
  document.querySelector('button[type="button"]').addEventListener("click", () => resent++);
  const result = await fields.complete(target(document, fields), "001234");
  assert.equal(result.submitted, true);
  assert.equal(submitted, 1);
  assert.equal(resent, 0);
});

test("completion waits for controlled forms to enable their verification button", async () => {
  const { document, fields } = fixture('<form><input autocomplete="one-time-code"><button disabled>로그인</button></form>');
  let submitted = 0;
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); submitted++; });
  document.querySelector("input").addEventListener("input", () => setTimeout(() => { document.querySelector("button").disabled = false; }, 220));
  assert.equal((await fields.complete(target(document, fields), "123456")).submitted, true);
  assert.equal(submitted, 1);
});

test("sites that submit on input are not submitted twice", async () => {
  const { document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  let submitted = 0;
  const form = document.querySelector("form");
  form.addEventListener("submit", event => { event.preventDefault(); submitted++; });
  document.querySelector("input").addEventListener("input", () => form.requestSubmit());
  assert.equal((await fields.complete(target(document, fields), "123456")).submitted, true);
  assert.equal(submitted, 1);
});

test("unrelated actions, other forms, ambiguous buttons and invalid forms are never submitted", async () => {
  for (const buttons of ['<button>Pay now</button>', '<button>Confirm transfer</button>', '<input aria-label="금액" value="10000"><button>확인</button>', '<button>Continue</button><button>Verify</button>', '<input required name="password"><button>Verify</button>']) {
    const { document, fields } = fixture(`<form><input autocomplete="one-time-code">${buttons}</form><form><button>Login</button></form>`);
    let clicks = 0;
    document.addEventListener("click", event => { event.preventDefault(); clicks++; });
    assert.equal((await fields.complete(target(document, fields), "123456")).submitted, false);
    assert.equal(clicks, 0);
  }
});

test("cancellation between filling and submitting prevents the click", async () => {
  const { document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  let current = true, clicks = 0;
  document.addEventListener("click", () => clicks++);
  const work = fields.complete(target(document, fields), "123456", () => current);
  current = false;
  assert.equal((await work).submitted, false);
  assert.equal(clicks, 0);
});

test("field labels are bounded and stripped of bidirectional control characters", () => {
  const { document, fields } = fixture('<label for="otp">인증\u202e번호 ' + "x".repeat(100) + '</label><input id="otp">');
  const label = fields.label(target(document, fields));
  assert.equal(label.includes("\u202e"), false);
  assert.ok(label.length <= 80);
});

test("completion retries once when a controlled input clears the inserted code", async () => {
  for (const synchronous of [true, false]) {
    const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>로그인</button></form>');
    const input = document.querySelector("input");
    let writes = 0, submits = 0;
    input.addEventListener("input", event => {
      assert.equal(event.inputType, "insertReplacementText");
      assert.equal(event.data, "001234");
      if (++writes === 1) {
        if (synchronous) input.value = "";
        else setTimeout(() => { input.value = ""; }, 20);
      }
    });
    document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); submits++; });
    const result = await fields.complete(target(document, fields), "001234");
    assert.equal(result.success, true);
    assert.equal(result.submitted, true);
    assert.equal(input.value, "001234");
    assert.equal(document.activeElement, input);
    assert.equal(writes, 2);
    assert.equal(submits, 1);
    window.close();
  }
});

test("repeatedly rejected input fails without claiming success or clicking verify", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  let writes = 0, clicks = 0;
  const input = document.querySelector("input");
  input.addEventListener("input", () => { writes++; setTimeout(() => { input.value = ""; }, 20); });
  document.addEventListener("click", () => clicks++);
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, false);
  assert.equal(result.submitted, false);
  assert.equal(input.value, "");
  assert.equal(writes, 2);
  assert.equal(clicks, 0);
  window.close();
});

test("a replaced OTP input in the same form is rescanned and verified once", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  let submits = 0;
  const old = document.querySelector("input");
  old.addEventListener("input", () => setTimeout(() => {
    old.outerHTML = '<input id="replacement" autocomplete="one-time-code">';
  }, 20), { once: true });
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); submits++; });
  const result = await fields.complete(target(document, fields), "001234");
  assert.equal(result.success, true);
  assert.equal(result.submitted, true);
  assert.equal(document.getElementById("replacement").value, "001234");
  assert.equal(submits, 1);
  window.close();
});

test("focus handlers can replace the OTP widget before its first value is inserted", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  const old = document.querySelector("input");
  old.addEventListener("focus", () => { old.outerHTML = '<input id="replacement" autocomplete="one-time-code">'; }, { once: true });
  document.querySelector("form").addEventListener("submit", event => event.preventDefault());
  assert.equal((await fields.complete(target(document, fields), "123456")).submitted, true);
  assert.equal(old.value, "");
  assert.equal(document.getElementById("replacement").value, "123456");
  window.close();
});

test("a user-edited value is not overwritten or treated as a successful fill", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  let clicks = 0;
  const input = document.querySelector("input");
  input.addEventListener("input", () => setTimeout(() => { input.value = "12"; }, 20));
  document.addEventListener("click", () => clicks++);
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, false);
  assert.equal(input.value, "12");
  assert.equal(clicks, 0);
  window.close();
});

test("retry cannot move a code into another form or a replacement document section", async () => {
  const { window, document, fields } = fixture('<form id="original"><input autocomplete="one-time-code"><button>Verify</button></form>');
  document.querySelector("input").addEventListener("input", () => setTimeout(() => {
    document.getElementById("original").outerHTML = '<form><input id="other" autocomplete="one-time-code"><button>Verify</button></form>';
  }, 20));
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, false);
  assert.equal(document.getElementById("other").value, "");
  window.close();
});

test("completion waits beyond two seconds for the site's login button", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button disabled>로그인</button></form>');
  let submits = 0;
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); submits++; });
  document.querySelector("input").addEventListener("input", () => setTimeout(() => { document.querySelector("button").disabled = false; }, 2200));
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, true);
  assert.equal(result.submitted, true);
  assert.equal(submits, 1);
  window.close();
});

test("a verification button mounted after input is awaited within its form", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"></form>');
  let submits = 0;
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); submits++; });
  document.querySelector("input").addEventListener("input", () => setTimeout(() => {
    document.querySelector("form").insertAdjacentHTML("beforeend", '<button>로그인</button>');
  }, 600));
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, true);
  assert.equal(result.submitted, true);
  assert.equal(submits, 1);
  window.close();
});

test("formless login controls beside a wrapped OTP input can be activated", async () => {
  const { window, document, fields } = fixture('<section><label><input placeholder="인증번호"></label><button>인증번호 재전송</button><button id="login">로그인</button></section>');
  let clicks = 0;
  document.getElementById("login").addEventListener("click", () => clicks++);
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, true);
  assert.equal(result.submitted, true);
  assert.equal(clicks, 1);
  window.close();
});

test("SMS completion ignores alternative passkey, password, email and QR login buttons", async () => {
  const { window, document, fields } = fixture('<section><label><input placeholder="인증번호"></label><button id="login">로그인</button>'
    + ['패스키 로그인', '비밀번호 로그인', '이메일 로그인', 'QR코드 로그인', 'Sign in with passkey'].map(text => `<button class="alternative">${text}</button>`).join('') + '</section>');
  let clicks = 0, alternatives = 0;
  document.getElementById("login").addEventListener("click", () => clicks++);
  document.querySelectorAll(".alternative").forEach(button => button.addEventListener("click", () => alternatives++));
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.submitted, true);
  assert.equal(clicks, 1);
  assert.equal(alternatives, 0);
  window.close();
});

test("formless completion does not reach past another OTP widget to find a login button", async () => {
  const { window, document, fields } = fixture('<section><label><input id="selected" autocomplete="one-time-code"></label><input autocomplete="one-time-code"><button>로그인</button></section>');
  let clicks = 0;
  const input = document.getElementById("selected");
  input.focus();
  document.addEventListener("click", () => clicks++);
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, true);
  assert.equal(result.submitted, false);
  assert.equal(clicks, 0);
  window.close();
});

test("newly introduced payment controls prevent automatic submission while a button is pending", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button disabled>확인</button></form>');
  let clicks = 0;
  document.addEventListener("click", () => clicks++);
  document.querySelector("input").addEventListener("input", () => setTimeout(() => {
    document.querySelector("button").disabled = false;
    document.querySelector("form").insertAdjacentHTML("beforeend", '<input aria-label="금액" value="10000">');
  }, 150));
  const result = await fields.complete(target(document, fields), "123456");
  assert.equal(result.success, true);
  assert.equal(result.submitted, false);
  assert.equal(clicks, 0);
  window.close();
});

test("cancellation from a focus handler prevents the code from being inserted", async () => {
  const { window, document, fields } = fixture('<input autocomplete="one-time-code">');
  let current = true;
  document.querySelector("input").addEventListener("focus", () => { current = false; });
  const result = await fields.complete(target(document, fields), "123456", () => current);
  assert.equal(result.success, false);
  assert.equal(document.querySelector("input").value, "");
  window.close();
});

function bridge(html) {
  const item = fixture(html);
  const { window } = item;
  window.isSecureContext = true;
  const ports = [];
  window.browser = { runtime: { connectNative() {
    const messages = [], listeners = [], disconnected = [];
    const port = { messages, onMessage: { addListener: fn => listeners.push(fn) },
      onDisconnect: { addListener: fn => disconnected.push(fn) }, postMessage: data => messages.push(data),
      disconnect: () => disconnected.forEach(fn => fn()), deliver: data => Promise.all(listeners.map(fn => fn(data))) };
    ports.push(port);
    return port;
  } } };
  window.eval(fs.readFileSync(path.join(assets, "content.js"), "utf8"));
  return { ...item, ports, base: { document: ports[0].messages[0].document, href: window.location.href, request: "1" } };
}

test("the bridge rejects wrong documents, URLs and requests; reconnects on back navigation", async () => {
  const { window, document, ports, base } = bridge('<input id="otp" autocomplete="one-time-code">');
  const port = ports[0];
  await port.deliver({ ...base, type: "scan", document: "old-document" });
  await port.deliver({ ...base, type: "scan", href: "https://other.example/verify" });
  assert.equal(port.messages.length, 1);
  await port.deliver({ ...base, type: "scan", code: "001234" });
  assert.equal(port.messages[1].automatic, "0");
  await port.deliver({ ...base, request: "old-request", type: "fill", field: "0", code: "987654" });
  assert.equal(document.getElementById("otp").value, "");
  await port.deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.equal(document.getElementById("otp").value, "001234");
  assert.equal(port.messages.find(message => message.type === "filled" && message.request === "1").success, true);
  assert.equal(JSON.stringify(port.messages).includes("001234"), false);
  window.dispatchEvent(new window.PageTransitionEvent("pagehide"));
  window.dispatchEvent(new window.PageTransitionEvent("pageshow", { persisted: true }));
  assert.equal(ports.length, 2);
  assert.notEqual(ports[1].messages[0].document, base.document);
  document.getElementById("otp").value = "";
  const next = ports[1], second = { ...base, document: next.messages[0].document, request: "2" };
  await next.deliver({ ...second, type: "scan" });
  await next.deliver({ ...second, type: "cancel" });
  await next.deliver({ ...second, type: "fill", field: "0", code: "123456" });
  assert.equal(document.getElementById("otp").value, "");
  window.close();
});

test("the bridge reports newly mounted OTP forms without sending page values", async () => {
  const { window, document, ports } = bridge('<input name="phone" value="01012345678">');
  assert.equal(ports[0].messages[0].hasTarget, false);
  document.body.insertAdjacentHTML("beforeend", '<input autocomplete="one-time-code">');
  await tick();
  assert.equal(ports[0].messages.at(-1).hasTarget, true);
  assert.equal(JSON.stringify(ports[0].messages).includes("01012345678"), false);
  window.close();
});


test("request-code screens arm reception before the OTP widget exists", async () => {
  const { window, document, ports, base } = bridge('<form><input name="phone"><button type="button">인증번호 받기</button></form>');
  assert.equal(ports[0].messages[0].hasTarget, true);
  assert.equal(ports[0].messages[0].emptyTarget, false);
  const scanning = ports[0].deliver({ ...base, type: "scan", code: "123456" });
  setTimeout(() => document.body.insertAdjacentHTML("beforeend", '<input autocomplete="one-time-code">'), 200);
  await scanning;
  assert.equal(ports[0].messages.find(message => message.type === "fields").automatic, "0");
  window.close();
});

test("the bridge reports a cleared OTP widget so a retry can auto-arm", async () => {
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button type="button">Resend code</button></form>');
  await ports[0].deliver({ ...base, type: "scan", code: "123456" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "123456" });
  await tick();
  assert.equal(ports[0].messages.findLast(message => message.type === "state").emptyTarget, false);
  document.querySelector("input").value = "";
  document.querySelector("input").dispatchEvent(new window.Event("input", { bubbles: true }));
  await tick();
  assert.equal(ports[0].messages.findLast(message => message.type === "state").emptyTarget, true);
  window.close();
});


test("a consent result received before browser visibility resumes waits for the active document", async () => {
  const { window, document, ports, base } = bridge('<input autocomplete="one-time-code">');
  let visibility = "hidden";
  Object.defineProperty(document, "visibilityState", { get: () => visibility });
  const scanning = ports[0].deliver({ ...base, type: "scan", code: "123456" });
  setTimeout(() => { visibility = "visible"; }, 150);
  await scanning;
  assert.equal(ports[0].messages.find(message => message.type === "fields").automatic, "0");
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "123456" });
  assert.equal(document.querySelector("input").value, "123456");
  window.close();
});

test("approved SMS waits for a field that mounts more than two seconds later", async () => {
  const { window, document, ports, base } = bridge('<form><button type="button">인증번호 발송</button><button>로그인</button></form>');
  const scanning = ports[0].deliver({ ...base, type: "scan", code: "001234" });
  setTimeout(() => document.querySelector("form").insertAdjacentHTML("afterbegin", '<input autocomplete="one-time-code">'), 2200);
  await scanning;
  assert.equal(ports[0].messages.find(message => message.type === "fields").automatic, "0");
  document.querySelector("form").addEventListener("submit", event => event.preventDefault());
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.equal(document.querySelector("input").value, "001234");
  assert.equal(ports[0].messages.find(message => message.type === "filled").submitted, true);
  window.close();
});

test("cancelling a scan prevents late-mounted inputs from receiving the previous code", async () => {
  const { window, document, ports, base } = bridge('<form><button type="button">인증번호 발송</button></form>');
  const scanning = ports[0].deliver({ ...base, type: "scan", code: "123456" });
  await ports[0].deliver({ ...base, type: "cancel" });
  document.querySelector("form").insertAdjacentHTML("afterbegin", '<input autocomplete="one-time-code">');
  await scanning;
  assert.equal(ports[0].messages.some(message => message.type === "fields"), false);
  assert.equal(document.querySelector("input").value, "");
  window.close();
});

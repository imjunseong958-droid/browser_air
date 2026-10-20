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

test("formless login finds a sibling button beyond four decorative input wrappers", async () => {
  const { window, document, fields } = fixture('<section><div><div><div><label><input placeholder="인증번호"></label></div></div></div>'
    + '<button id="login">로그인</button><button>패스키 로그인</button></section>');
  let clicks = 0;
  document.getElementById("login").addEventListener("click", () => clicks++);
  const result = await fields.complete(target(document, fields), "001234");
  assert.equal(result.submitted, true);
  assert.equal(clicks, 1);
  window.close();
});

test("formless completion never borrows a login button belonging to another form", async () => {
  const { window, document, fields } = fixture('<section><label><input placeholder="인증번호"></label><form><button>로그인</button></form></section>');
  let clicks = 0;
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); clicks++; });
  const result = await fields.complete(target(document, fields), "001234");
  assert.equal(result.submitted, false);
  assert.equal(clicks, 0);
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

function bridge(html, native = false) {
  const item = fixture(html);
  const { window } = item;
  window.isSecureContext = true;
  const ports = [];
  window.browser = { runtime: { connectNative() {
    const messages = [], listeners = [], disconnected = [];
    const port = { messages, onMessage: { addListener: fn => listeners.push(fn) },
      onDisconnect: { addListener: fn => disconnected.push(fn) }, postMessage: data => {
        messages.push(data);
        // Most fixtures model a device without a native editor; native cases acknowledge explicitly.
        if (data.type === "input" && typeof native === "function") queueMicrotask(() => native(data, port, window));
        else if (data.type === "input" && !native) queueMicrotask(() => port.deliver({ ...data, type: "input-result", success: false }));
      },
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


test("an OTP hint selects the code automatically without touching the name field", () => {
  for (const attributes of ['name="verificationCode"', 'autocomplete="one-time-code"']) {
    const { window, document, fields } = fixture(`<form><input placeholder="이름"><input ${attributes}><button>로그인</button></form>`);
    document.querySelector('input:last-of-type').focus();
    const result = fields.scan(document, "001234");
    assert.equal(result.automatic.inputs[0], document.querySelector("input:last-of-type"));
    assert.equal(result.targets.length, 2);
    window.close();
  }
});

test("phone-prefixed code identifiers are detected automatically", () => {
  const { window, document, fields } = fixture('<form><input placeholder="이름"><input name="mobileAuthCode"><button>로그인</button></form>');
  const result = fields.scan(document, "001234");
  assert.equal(result.automatic.inputs[0].name, "mobileAuthCode");
  assert.equal(result.targets.length, 2);
  assert.equal(fields.label(result.targets.find(item => item.inputs[0].name === "mobileAuthCode")), "");
  window.close();
});

test("the bridge automatically selects a phone-prefixed code control beside a name field", async () => {
  const { window, document, ports, base } = bridge('<form><input placeholder="이름"><input name="mobileAuthCode"><button type="button">인증번호 발송</button><button>로그인</button></form>');
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  const choices = ports[0].messages.find(message => message.type === "fields");
  assert.equal(choices.automatic, "0");
  assert.equal(choices.fields.length, 2);
  const selected = choices.fields.find(item => item.label === "");
  document.querySelector("form").addEventListener("submit", event => event.preventDefault());
  await ports[0].deliver({ ...base, type: "fill", field: selected.id, code: "001234" });
  assert.equal(document.querySelector('[placeholder="이름"]').value, "");
  assert.equal(document.querySelector('[name="mobileAuthCode"]').value, "001234");
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, true);
  window.close();
});

test("a verification click that clears the code cannot report a successful fill", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button type="button">로그인</button></form>');
  document.querySelector("button").addEventListener("click", () => setTimeout(() => { document.querySelector("input").value = ""; }, 20));
  const result = await fields.complete(target(document, fields), "001234");
  assert.equal(result.success, false);
  assert.equal(result.submitted, true);
  assert.equal(document.querySelector("input").value, "");
  window.close();
});

test("a submission fired after input rejection cannot bypass value verification", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  document.querySelector("form").addEventListener("submit", event => event.preventDefault());
  document.querySelector("input").addEventListener("input", () => {
    document.querySelector("input").value = "";
    document.querySelector("form").requestSubmit();
  });
  assert.equal((await fields.complete(target(document, fields), "001234")).success, false);
  window.close();
});

test("approved SMS infers an unnamed code widget beside a name without choosing", async () => {
  const { window, document, ports, base } = bridge('<form><input id="verificationName" placeholder="이름"><input placeholder="--" maxlength="6"><button>로그인</button></form>');
  assert.equal(target(document, window.AirSmsFields), null);
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  const choices = ports[0].messages.find(message => message.type === "fields");
  assert.equal(choices.automatic, "0");
  document.querySelector("form").addEventListener("submit", event => event.preventDefault());
  await ports[0].deliver({ ...base, type: "fill", field: choices.automatic, code: "001234" });
  assert.equal(document.getElementById("verificationName").value, "");
  assert.equal(document.querySelector('[placeholder="--"]').value, "001234");
  assert.equal(ports[0].messages.find(message => message.type === "filled").submitted, true);
  window.close();
});

test("approved SMS cannot infer personal, phone or coupon fields or resolve two unknown controls", () => {
  for (const html of ['<input id="verificationName" placeholder="이름" maxlength="6">', '<input placeholder="휴대폰번호" id="verificationPhone" maxlength="6">',
    '<input name="couponCode" maxlength="6">', '<input name="captchaAnswer" maxlength="6">',
    '<input name="password" maxlength="6">', '<input name="birthDate" maxlength="8">', '<input maxlength="6"><input maxlength="6">']) {
    const { window, document, fields } = fixture(html);
    assert.equal(target(document, fields, "001234"), null);
    window.close();
  }
});

test("the bridge requests Android input once when a site rejects synthetic insertion", async () => {
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button>Verify</button></form>', true);
  document.querySelector("input").addEventListener("input", event => { if (!event.isTrusted) event.target.value = ""; });
  document.querySelector("form").addEventListener("submit", event => event.preventDefault());
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  const filling = ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  for (let attempt = 0; attempt < 20 && !ports[0].messages.some(message => message.type === "input"); attempt++) await tick(50);
  assert.equal(ports[0].messages.filter(message => message.type === "input").length, 1);
  assert.equal(document.activeElement, document.querySelector("input"));
  assert.equal(document.activeElement.value, "");
  await ports[0].deliver({ ...base, request: "wrong", type: "input-result", success: true });
  assert.equal(ports[0].messages.some(message => message.type === "filled"), false);
  // Model only the engine's accepted value; trusted-event behavior is covered in Gecko instrumentation.
  document.querySelector("input").value = "001234";
  await ports[0].deliver({ ...base, type: "input-result", success: true });
  await filling;
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, true);
  assert.equal(JSON.stringify(ports[0].messages).includes("001234"), false);
  window.close();
});

test("cancellation while Android recovery is pending discards its late response", async () => {
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button>Verify</button></form>', true);
  document.querySelector("input").addEventListener("input", event => { event.target.value = ""; });
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  const filling = ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  for (let attempt = 0; attempt < 20 && !ports[0].messages.some(message => message.type === "input"); attempt++) await tick(50);
  assert.equal(ports[0].messages.filter(message => message.type === "input").length, 1);
  await ports[0].deliver({ ...base, type: "cancel" });
  await ports[0].deliver({ ...base, type: "input-result", success: true });
  await filling;
  assert.equal(ports[0].messages.some(message => message.type === "filled"), false);
  assert.equal(document.querySelector("input").value, "");
  window.close();
});

test("Android input is requested before writing a controlled input with a disabled button", async () => {
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button disabled>Verify</button></form>', true);
  document.querySelector("form").addEventListener("submit", event => event.preventDefault());
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  const filling = ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  for (let attempt = 0; attempt < 40 && !ports[0].messages.some(message => message.type === "input"); attempt++) await tick(50);
  assert.equal(ports[0].messages.filter(message => message.type === "input").length, 1);
  assert.equal(document.querySelector("input").value, "");
  document.querySelector("input").value = "001234";
  document.querySelector("button").disabled = false;
  await ports[0].deliver({ ...base, type: "input-result", success: true });
  await filling;
  assert.equal(ports[0].messages.find(message => message.type === "filled").submitted, true);
  window.close();
});

test("Android editor readiness retries succeed without synthetic input or duplicate verification", async () => {
  let requests = 0, ready = false, syntheticWrites = 0, clicks = 0;
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button>로그인</button></form>',
    (data, port, window) => {
      if (++requests === 1) setTimeout(() => { ready = true; }, 1100);
      const success = ready;
      if (success) window.document.querySelector("input").value = "001234";
      port.deliver({ ...data, type: "input-result", success, retry: !success });
    });
  document.querySelector("input").addEventListener("input", event => { syntheticWrites++; event.target.value = ""; });
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); clicks++; });
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.ok(requests > 1 && requests <= 10);
  assert.equal(syntheticWrites, 0);
  assert.equal(clicks, 1);
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, true);
  window.close();
});

test("an editor that never becomes ready exhausts its bounded retries and retains manual fallback", async () => {
  let requests = 0, clicks = 0;
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button>로그인</button></form>',
    (data, port) => { requests++; port.deliver({ ...data, type: "input-result", success: false, retry: true }); });
  document.querySelector("input").addEventListener("input", event => { event.target.value = ""; });
  document.querySelector("button").addEventListener("click", event => { event.preventDefault(); clicks++; });
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.ok(requests > 1 && requests <= 10);
  assert.equal(clicks, 0);
  assert.equal(document.querySelector("input").value, "");
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, false);
  window.close();
});

test("a failed attempted native write is terminal rather than treated as editor readiness", async () => {
  let requests = 0;
  const { window, document, ports, base } = bridge('<input autocomplete="one-time-code">',
    (data, port) => { requests++; port.deliver({ ...data, type: "input-result", success: false, retry: false }); });
  document.querySelector("input").addEventListener("input", event => { event.target.value = ""; });
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.equal(requests, 1);
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, false);
  window.close();
});

test("cancelling between editor readiness retries prevents another native request", async () => {
  let requests = 0;
  const { window, document, ports, base } = bridge('<input autocomplete="one-time-code">',
    async (data, port) => {
      requests++;
      await port.deliver({ ...data, type: "input-result", success: false, retry: true });
      await port.deliver({ ...data, type: "cancel" });
    });
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.equal(requests, 1);
  assert.equal(document.querySelector("input").value, "");
  assert.equal(ports[0].messages.some(message => message.type === "filled"), false);
  window.close();
});

test("an edit between editor readiness retries is preserved without verification", async () => {
  let requests = 0, clicks = 0;
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button>로그인</button></form>',
    (data, port, window) => {
      requests++;
      port.deliver({ ...data, type: "input-result", success: false, retry: true });
      window.document.querySelector("input").value = "12";
    });
  document.querySelector("button").addEventListener("click", event => { event.preventDefault(); clicks++; });
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.equal(requests, 1);
  assert.equal(document.querySelector("input").value, "12");
  assert.equal(clicks, 0);
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, false);
  window.close();
});

test("an unacknowledged native request times out without retrying a possibly queued write", async () => {
  const { window, document, ports, base } = bridge('<input autocomplete="one-time-code">', true);
  document.querySelector("input").addEventListener("input", event => { event.target.value = ""; });
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  assert.equal(ports[0].messages.filter(message => message.type === "input").length, 1);
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, false);
  window.close();
});

test("decorative wrapping labels cannot hide phone or OTP placeholders", () => {
  const { window, document, fields } = fixture('<section><label>🇰🇷+82<input type="tel" placeholder="휴대폰번호"></label>'
    + '<label>03:00<input type="tel" placeholder="인증번호 6자리" maxlength="6"></label><button>로그인</button></section>');
  const result = fields.scan(document, "001234");
  assert.equal(result.automatic?.inputs[0].placeholder, "인증번호 6자리");
  assert.equal(result.targets.length, 1);
  assert.equal(fields.hasTarget(document), true);
  window.close();
});

test("phone entry is never offered as an OTP even with a misleading verification identifier", () => {
  for (const attributes of ['name="verificationPhone" maxlength="6"', 'autocomplete="tel-national"', 'autocomplete="one-time-code"']) {
    const { window, document, fields } = fixture(`<label>🇰🇷+82<input type="tel" placeholder="휴대폰번호" ${attributes}></label><button>인증번호 발송</button>`);
    assert.equal(fields.hasTarget(document), true);
    assert.equal(fields.scan(document, "001234").targets.length, 0);
    window.close();
  }
});

test("a reused phone control cannot receive the code selected before its purpose changes", () => {
  const { window, document, fields } = fixture('<input placeholder="인증번호" maxlength="6">');
  const selected = target(document, fields);
  document.querySelector("input").placeholder = "휴대폰번호";
  assert.equal(fields.fill(selected, "001234"), false);
  assert.equal(fields.canRecover(selected, "001234"), false);
  assert.equal(document.querySelector("input").value, "");
  window.close();
});

test("phone request transitions to a new wrapped OTP widget after message approval", async () => {
  const { window, document, ports, base } = bridge('<section><label>🇰🇷+82<input type="tel" placeholder="휴대폰번호" value="01000000000"></label>'
    + '<button>인증번호 발송</button></section>');
  assert.equal(ports[0].messages[0].emptyTarget, false);
  const scanning = ports[0].deliver({ ...base, type: "scan", code: "001234" });
  setTimeout(() => {
    document.querySelector("button").remove();
    document.querySelector("section").insertAdjacentHTML("beforeend", '<label>03:00<input placeholder="인증번호 6자리" maxlength="6"></label><button id="login">로그인</button>');
  }, 150);
  await scanning;
  const choice = ports[0].messages.find(message => message.type === "fields");
  assert.equal(choice.automatic, "0");
  assert.equal(choice.fields.length, 1);
  let clicks = 0;
  document.getElementById("login").addEventListener("click", () => clicks++);
  await ports[0].deliver({ ...base, type: "fill", field: choice.automatic, code: "001234" });
  assert.equal(document.querySelector('[placeholder="휴대폰번호"]').value, "01000000000");
  assert.equal(document.querySelector('[maxlength="6"]').value, "001234");
  assert.equal(clicks, 1);
  window.close();
});

test("native input precedes an enabled login button when synthetic events would be ignored", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>로그인</button></form>');
  let accepted = "", submitted = "", nativeWrites = 0, syntheticWrites = 0;
  document.querySelector("input").addEventListener("input", () => syntheticWrites++);
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); submitted = accepted; });
  const result = await fields.complete(target(document, fields), "001234", () => true, fields.fill, (selected, code) => {
    nativeWrites++;
    selected.inputs[0].value = accepted = code;
    return true;
  });
  assert.equal(result.submitted, true);
  assert.equal(submitted, "001234");
  assert.equal(nativeWrites, 1);
  assert.equal(syntheticWrites, 0);
  window.close();
});

test("an edit during native insertion is preserved and prevents verification", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>로그인</button></form>');
  let clicks = 0;
  document.querySelector("button").addEventListener("click", () => clicks++);
  const result = await fields.complete(target(document, fields), "001234", () => true, fields.fill, selected => {
    selected.inputs[0].value = "12";
    return false;
  });
  assert.equal(result.success, false);
  assert.equal(document.querySelector("input").value, "12");
  assert.equal(clicks, 0);
  window.close();
});

test("a referenced OTP label becoming visible to the detector updates the bridge state", async () => {
  const { window, document, ports } = bridge('<span id="purpose">전화번호</span><input aria-labelledby="purpose">');
  assert.equal(ports[0].messages[0].hasTarget, false);
  document.getElementById("purpose").firstChild.data = "인증번호";
  await tick();
  assert.equal(ports[0].messages.at(-1).hasTarget, true);
  assert.equal(ports[0].messages.at(-1).emptyTarget, true);
  window.close();
});

test("native acknowledgement without a retained value cannot claim a successful fill", async () => {
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button>로그인</button></form>', true);
  document.querySelector("input").addEventListener("input", event => { event.target.value = ""; });
  let clicks = 0;
  document.querySelector("button").addEventListener("click", () => clicks++);
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  const work = ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  for (let attempt = 0; attempt < 20 && !ports[0].messages.some(message => message.type === "input"); attempt++) await tick(50);
  await ports[0].deliver({ ...base, type: "input-result", success: true });
  await work;
  assert.equal(ports[0].messages.find(message => message.type === "filled").success, false);
  assert.equal(ports[0].messages.filter(message => message.type === "input").length, 1);
  assert.equal(clicks, 0);
  window.close();
});

test("hidden or edited controls cannot receive Android recovery", () => {
  const { window, document, fields } = fixture('<input autocomplete="one-time-code">');
  const selected = target(document, fields);
  document.querySelector("input").value = "001234";
  assert.equal(fields.canRecover(selected, "001234"), true);
  document.querySelector("input").value = "12";
  assert.equal(fields.canRecover(selected, "001234"), false);
  document.querySelector("input").value = "";
  document.querySelector("input").hidden = true;
  assert.equal(fields.canRecover(selected, "001234"), false);
  window.close();
});

test("a retained code with an unresponsive verification button never claims completion", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button disabled>Verify</button></form>');
  let recovery = 0;
  const result = await fields.complete(target(document, fields), "001234", () => true, () => { recovery++; return false; });
  assert.equal(recovery, 1);
  assert.equal(result.success, false);
  assert.equal(result.submitted, false);
  assert.equal(document.querySelector("input").value, "001234");
  window.close();
});

test("a verification attempt that removes its widget ends without claiming the value is still filled", async () => {
  const { window, document, fields } = fixture('<form><input autocomplete="one-time-code"><button>Verify</button></form>');
  document.querySelector("form").addEventListener("submit", event => { event.preventDefault(); event.target.remove(); });
  const result = await fields.complete(target(document, fields), "001234");
  assert.equal(result.submitted, true);
  assert.equal(result.success, false);
  assert.equal(result.departed, true);
  window.close();
});

// Coupang's mobile-web code step (/login/otpLogin.pang, m-sms-login.min.js 2026-10-19 build) when
// data-auto-otp is enabled: six number boxes, the first accepting a whole autofilled code. The
// handlers below mirror that script; the PC variant is a single #smsCode number input instead.
function coupangSplit() {
  const digits = Array.from({ length: 6 }, (_, i) => `<input class="code-digit" data-index="${i}" type="number" inputmode="numeric"`
    + (i === 0 ? ' autocomplete="one-time-code" maxlength="6">' : ' maxlength="1">')).join("");
  const item = fixture('<div id="smsLogin" class="sms-login" data-phase="VERIFY_CODE"><div class="sms-login__container">'
    + '<div class="input-row"><div class="input-row"><div id="verification-code">' + digits
    + '<div class="loading-overlay"><div class="loading-spinner"></div></div></div><input id="smsCode" type="hidden"></div></div>'
    + '<p>00:59 <button class="send-again" id="sendAgain" disabled>재발송</button></p>'
    + '<button id="loginWithSmsCode" disabled>로그인</button>'
    + '<button class="link-btn" id="loginLink"><div>비밀번호로 로그인</div></button></div></div>');
  const { document } = item;
  const boxes = [...document.querySelectorAll(".code-digit")], code = document.getElementById("smsCode");
  const login = document.getElementById("loginWithSmsCode");
  const collect = () => {
    let value = "", last = 0;
    boxes.forEach((box, index) => { if (box.value) last = index; value += box.value; });
    code.value = value;
    boxes[last].focus();
    login.disabled = last !== 5;
  };
  for (const box of boxes) {
    box.addEventListener("input", () => {
      const value = box.value;
      if (value.length === 6) {
        if (/^\d{6}$/.test(value)) {
          boxes[0].setAttribute("maxlength", "1");
          boxes.forEach((digit, index) => { digit.value = String(Number(value[index])); });
          code.value = value;
          login.disabled = false;
          setTimeout(() => boxes[5].focus(), 300);
        }
      } else if (value.length === 1) {
        const digit = value.replace(/[^0-9]/g, ""), index = Number(box.dataset.index);
        box.value = digit;
        if (digit && index < 5) boxes[index + 1].focus();
        collect();
      }
    });
  }
  item.submitted = [];
  login.addEventListener("click", () => item.submitted.push(code.value));
  return item;
}

test("Coupang's mobile split widget is filled as one group and its login button is activated", async () => {
  const item = coupangSplit();
  const { window, document, fields } = item;
  const selected = target(document, fields, "001234");
  const boxes = [...document.querySelectorAll(".code-digit")];
  assert.equal(selected.inputs.length, 6);
  assert.ok(selected.inputs.every((input, index) => input === boxes[index]));
  const result = await fields.complete(selected, "001234", () => true, fields.fill, () => assert.fail("split widgets use DOM input"));
  assert.equal(result.success, true);
  assert.equal(result.submitted, true);
  assert.deepEqual(item.submitted, ["001234"]);
  window.close();
});

test("the Coupang split widget completes through the bridge after Android Allow", async () => {
  const item = coupangSplit();
  const { window, document } = item;
  const { ports, base } = (() => {
    const ports = [];
    window.isSecureContext = true;
    window.browser = { runtime: { connectNative() {
      const messages = [], listeners = [];
      const port = { messages, onMessage: { addListener: fn => listeners.push(fn) }, onDisconnect: { addListener() {} },
        postMessage: data => {
          messages.push(data);
          if (data.type === "input") queueMicrotask(() => port.deliver({ ...data, type: "input-result", success: false }));
        },
        disconnect() {}, deliver: data => Promise.all(listeners.map(fn => fn(data))) };
      ports.push(port);
      return port;
    } } };
    window.eval(fs.readFileSync(path.join(assets, "content.js"), "utf8"));
    return { ports, base: { document: ports[0].messages[0].document, href: window.location.href, request: "1" } };
  })();
  assert.equal(ports[0].messages[0].hasTarget, true);
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  const choice = ports[0].messages.find(message => message.type === "fields");
  assert.equal(choice.fields.length, 1);
  await ports[0].deliver({ ...base, type: "fill", field: choice.automatic, code: "001234" });
  const filled = ports[0].messages.find(message => message.type === "filled");
  assert.deepEqual({ success: filled.success, submitted: filled.submitted }, { success: true, submitted: true });
  assert.equal(document.getElementById("smsCode").value, "001234");
  assert.deepEqual(item.submitted, ["001234"]);
  window.close();
});

test("Coupang's PC code step with a single number input is filled and its login button activated", async () => {
  // Attributes observed on login.coupang.com/login/otpLogin.pang; the handler mirrors m-sms-login.min.js.
  const { window, document, fields } = fixture('<div id="smsLogin" class="sms-login"><div class="sms-login__container">'
    + '<div class="input-row"><div class="input-row"><input class="input-field verify-number" id="smsCode" placeholder="인증번호 6자리"'
    + ' aria-label="인증번호 6자리" autocomplete="one-time-code" autofocus inputmode="numeric" type="number"></div></div>'
    + '<button class="send-again" id="sendAgain" disabled>재발송</button><button id="loginWithSmsCode" disabled>로그인</button>'
    + '<button class="link-btn" id="loginLink"><div>비밀번호로 로그인</div></button></div></div>');
  const input = document.getElementById("smsCode"), login = document.getElementById("loginWithSmsCode");
  input.addEventListener("input", event => { login.disabled = !event.target.value; });
  const submitted = [];
  login.addEventListener("click", () => submitted.push(input.value));
  const result = await fields.complete(target(document, fields, "001234"), "001234");
  assert.deepEqual({ success: result.success, submitted: result.submitted }, { success: true, submitted: true });
  assert.deepEqual(submitted, ["001234"]);
  window.close();
});

test("a code spread from the first box into unlimited sibling boxes is adopted as the filled widget", async () => {
  for (const native of [false, true]) {
    const { window, document, fields } = fixture('<div><div id="boxes"><input id="first" autocomplete="one-time-code" maxlength="6">'
      + '<input><input><input><input><input></div><button id="login" disabled>로그인</button></div>');
    const boxes = [...document.querySelectorAll("#boxes input")], login = document.getElementById("login");
    boxes[0].addEventListener("input", () => {
      const value = boxes[0].value;
      if (value.length !== 6) return;
      boxes.forEach((box, index) => { box.value = value[index]; });
      login.disabled = false;
    });
    const submitted = [];
    login.addEventListener("click", () => submitted.push(boxes.map(box => box.value).join("")));
    const selected = target(document, fields, "001234");
    assert.equal(selected.inputs[0].id, "first");
    const result = await fields.complete(selected, "001234", () => true, fields.fill, native ? (chosen, code) => {
      chosen.inputs[0].value = code;
      chosen.inputs[0].dispatchEvent(new window.Event("input", { bubbles: true }));
      return true;
    } : null);
    assert.deepEqual({ success: result.success, submitted: result.submitted }, { success: true, submitted: true });
    assert.deepEqual(submitted, ["001234"]);
    window.close();
  }
});

test("a spread is adopted only when the following boxes contain exactly the code", async () => {
  const { window, document, fields } = fixture('<div><div><input id="first" autocomplete="one-time-code" maxlength="6">'
    + '<input><input><input><input><input></div><button id="login">로그인</button></div>');
  const boxes = [...document.querySelectorAll("input")];
  boxes[0].addEventListener("input", () => { boxes.forEach((box, index) => { box.value = "9876543"[index]; }); });
  let clicks = 0;
  document.getElementById("login").addEventListener("click", () => clicks++);
  const result = await fields.complete(target(document, fields, "001234"), "001234");
  assert.equal(result.success, false);
  assert.equal(result.reason, "changed");
  assert.equal(clicks, 0);
  window.close();
});

test("a transparent one-time-code input over decorative slots is filled, but unmarked transparent inputs are not", () => {
  const { window, document, fields } = fixture('<div class="slots"><span></span><span></span><span></span></div>'
    + '<input id="overlay" autocomplete="one-time-code" inputmode="numeric" maxlength="6" style="opacity:0"><input id="trap" style="opacity:0">');
  const selected = target(document, fields, "001234");
  assert.equal(selected.inputs[0].id, "overlay");
  assert.deepEqual(Array.from(fields.scan(document, "001234").targets, item => item.inputs[0].id), ["overlay"]);
  assert.equal(fields.fill(selected, "001234"), true);
  window.close();
});

test("after approved SMS the unnamed control focused by the site is chosen among several unnamed controls", () => {
  const { window, document, fields } = fixture('<input id="memo"><input id="entry" maxlength="6">');
  assert.equal(target(document, fields, "001234"), null);
  document.getElementById("entry").focus();
  assert.equal(target(document, fields, "001234").inputs[0].id, "entry");
  // Without an approved message the focused control is never treated as an OTP widget.
  assert.equal(target(document, fields), null);
  window.close();
});

test("a focused phone, name or coupon control is never inferred as the code field", () => {
  for (const html of ['<input id="phone" type="tel" placeholder="휴대폰번호">', '<input id="name" placeholder="이름">', '<input id="couponCode">']) {
    const { window, document, fields } = fixture(html + '<input id="other">' + '<input id="third">');
    document.querySelector("input").focus();
    assert.equal(target(document, fields, "001234"), null);
    window.close();
  }
});

test("a field that rejects the code reports why automatic input stopped", async () => {
  const { window, document, ports, base } = bridge('<form><input autocomplete="one-time-code"><button>로그인</button></form>');
  document.querySelector("input").addEventListener("input", event => setTimeout(() => { event.target.value = ""; }, 20));
  await ports[0].deliver({ ...base, type: "scan", code: "001234" });
  await ports[0].deliver({ ...base, type: "fill", field: "0", code: "001234" });
  const filled = ports[0].messages.find(message => message.type === "filled");
  assert.equal(filled.success, false);
  assert.equal(filled.reason, "changed");
  window.close();
});

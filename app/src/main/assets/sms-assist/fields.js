/* Detect OTP controls without collecting page values or using service-specific selectors. */
(() => {
  const otp = /one[\s_-]*time|\botp\b|verification|verify|security[\s_-]*code|auth(?:entication)?[\s_-]*code|sms[\s_-]*code|passcode|\bcode\b|인증|보안\s*(?:번호|코드)|확인\s*(?:번호|코드)/i;
  const unrelated = /phone|mobile|telephone|email|postal|zip|coupon|promo|amount|전화|휴대폰|이메일|우편|쿠폰|금액/i;
  const action = /verify|confirm|continue|sign[\s_-]*in|log[\s_-]*in|submit|next|인증|확인|로그인|계속|다음/i;
  const otherAction = /resend|send|request|cancel|back|pay|purchase|delete|transfer|withdraw|보내|전송|발송|요청|재전송|받기|취소|이전|결제|구매|삭제|송금|이체|출금/i;
  const marked = input => (input.getAttribute("autocomplete") || "")
    .toLowerCase().split(/\s+/).includes("one-time-code");
  const label = input => {
    const value = [...(input.labels || [])].map(item => item.textContent).join(" ")
      || input.getAttribute("aria-label") || input.getAttribute("placeholder") || "";
    return value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, "")
      .replace(/\s+/g, " ").trim().slice(0, 80);
  };
  const hint = input => [label(input), input.id, input.name].join(" ").replace(/([a-z])([A-Z])/g, "$1 $2");
  const visible = element => {
    if (!(element instanceof Element) || !element.isConnected || element.closest("[inert]")) return false;
    for (let node = element; node instanceof Element; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (["hidden", "collapse"].includes(style.visibility) || style.display === "none" || style.opacity === "0") return false;
    }
    return [...element.getClientRects()].some(rect => rect.width > 0 && rect.height > 0);
  };
  const structural = input => input instanceof HTMLInputElement && visible(input)
    && (["text", "tel", "number"].includes(input.type) || (input.type === "password" && marked(input)));
  const usable = (input, empty = true, allowDisabled = false) => structural(input)
    && (allowDisabled || !input.disabled) && !input.readOnly && (!empty || input.value === "");
  const controls = scope => [...scope.querySelectorAll('button, input[type="submit"], input[type="button"], [role="button"]')];
  const controlText = element => element.getAttribute("aria-label") || (element instanceof HTMLInputElement ? element.value : element.textContent) || "";
  const isAction = element => action.test(controlText(element)) && !otherAction.test(controlText(element));
  const identified = input => marked(input) || (otp.test(hint(input)) && !unrelated.test(hint(input)))
    || (!unrelated.test(hint(input)) && input.maxLength >= 4 && input.maxLength <= 10
      && (input.inputMode === "numeric" || input.type === "tel" || input.type === "number")
      && input.form && controls(input.form).some(control => /verify|인증/i.test(controlText(control)) && isAction(control)));

  function scan(document, code, allowDisabled = false) {
    const allInputs = [...document.querySelectorAll("input")].filter(structural);
    const inputs = allInputs.filter(input => usable(input, true, allowDisabled));
    const targets = [];
    const grouped = new Set();
    for (const input of inputs) {
      if (input.maxLength !== 1 || grouped.has(input)) continue;
      let group = null;
      // Split widgets may wrap each digit in its own element. Stop at the form boundary.
      for (let parent = input.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
        const siblings = allInputs.filter(item => parent.contains(item));
        if (siblings.length > 10 || siblings.some(item => item.maxLength !== 1 || item.form !== input.form)) break;
        if (siblings.length >= 4) {
          group = { inputs: siblings, container: parent };
          break;
        }
        if (parent === input.form) break;
      }
      if (!group) continue;
      group.inputs.forEach(item => grouped.add(item));
      if (!group.inputs.every(item => usable(item, true, allowDisabled))) continue;
      const groupHint = [group.container.getAttribute("aria-label"), group.container.id,
        group.container.querySelector("legend, label")?.textContent || ""].join(" ");
      group.identified = group.inputs.some(identified) || otp.test(groupHint);
      if (group.identified && (!code || group.inputs.length === code.length)) targets.push(group);
    }
    for (const input of inputs) {
      if (input.maxLength === 1 || (input.maxLength >= 0 && input.maxLength < 4)) continue;
      if (code && input.maxLength >= 0 && input.maxLength < code.length) continue;
      if (identified(input) || !unrelated.test(hint(input))) targets.push({ inputs: [input], container: input, identified: identified(input) });
    }
    const known = targets.filter(target => target.identified);
    const focused = known.filter(target => target.inputs.includes(document.activeElement));
    const automatic = known.length === 1 ? known[0] : focused.length === 1 ? focused[0] : null;
    const ordered = [...known, ...targets.filter(target => !target.identified)];
    return { targets: ordered.slice(0, 8), automatic };
  }

  function fill(target, code) {
    if (!/^[A-Za-z0-9]{4,10}$/.test(code) || !/[0-9]/.test(code) || !target?.inputs?.length) return false;
    const inputs = target.inputs;
    if (inputs.some(input => !usable(input) || (input.type === "number" && !/^[0-9]+$/.test(code)))) return false;
    if (inputs.length > 1 ? inputs.length !== code.length : inputs[0].maxLength >= 0 && inputs[0].maxLength < code.length) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
    // Set the entire split code first, so the last digit's handler sees a complete value.
    inputs.forEach((input, index) => setter.call(input, inputs.length === 1 ? code : code[index]));
    inputs.forEach(input => {
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    return inputs.map(input => input.value).join("") === code;
  }

  async function complete(target, code, current = () => true) {
    const first = target?.inputs?.[0];
    const form = first?.form;
    const scope = form || (target?.container?.closest('[role="group"], fieldset') || target?.container?.parentElement);
    const sensitive = /payment|checkout|transfer|withdraw|purchase|delete|결제|송금|이체|출금|구매|삭제/i;
    const sensitiveForm = scope && (sensitive.test([scope.id, scope.getAttribute("name"), scope.getAttribute("aria-label")].join(" "))
      || controls(scope).some(control => sensitive.test(controlText(control)))
      || [...scope.querySelectorAll("input")].some(input => visible(input) && /amount|금액/i.test(hint(input))));
    let submitted = false;
    const observeSubmit = () => { submitted = true; };
    const observeClick = event => { if (event.target.closest?.('button, input[type="submit"], input[type="button"], [role="button"]') && isAction(event.target.closest('button, input[type="submit"], input[type="button"], [role="button"]'))) submitted = true; };
    form?.addEventListener("submit", observeSubmit, true);
    scope?.addEventListener("click", observeClick, true);
    try {
      if (!current() || !fill(target, code)) return { success: false, submitted: false };
      // Give controlled forms time to enable their button, and sites time to auto-submit on input.
      for (let attempt = 0; attempt < 20; attempt++) {
        await new Promise(resolve => setTimeout(resolve, 100));
        if (submitted) return { success: true, submitted: true };
        if (!current() || target.inputs.some(input => !usable(input, false))
            || target.inputs.map(input => input.value).join("") !== code) break;
        if (!scope || !target.identified || sensitiveForm || (form && !form.checkValidity())) break;
        const candidates = controls(scope).filter(element => visible(element) && isAction(element)
          && (!form || !element.form || element.form === form));
        if (candidates.length !== 1) break;
        const button = candidates[0];
        if (button.disabled || button.getAttribute("aria-disabled") === "true" || scope.getAttribute("aria-busy") === "true") continue;
        // click preserves custom handlers as well as normal HTML form validation.
        button.click();
        return { success: true, submitted: true };
      }
      return { success: true, submitted };
    } finally {
      form?.removeEventListener("submit", observeSubmit, true);
      scope?.removeEventListener("click", observeClick, true);
    }
  }

  globalThis.AirSmsFields = { scan, label: target => label(target.inputs[0]) || "인증번호", fill, complete,
    hasTarget: document => scan(document, null, true).targets.some(target => target.identified)
      || controls(document).some(control => visible(control)
        && /(?:send|request|get|resend).*(?:code|otp)|인증.*(?:받기|요청|전송|발송)|(?:보안|확인)\s*(?:번호|코드).*(?:받기|요청|전송|발송)/i.test(controlText(control))) };
})();

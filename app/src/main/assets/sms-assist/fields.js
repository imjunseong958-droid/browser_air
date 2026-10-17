/* Detect OTP controls without collecting page values or using service-specific selectors. */
(() => {
  const otp = /one[\s_-]*time|\botp\b|verification|verify|security[\s_-]*code|auth(?:entication)?[\s_-]*code|sms[\s_-]*code|passcode|\bcode\b|인증|보안\s*(?:번호|코드)|확인\s*(?:번호|코드)/i;
  const unrelated = /phone|mobile|telephone|email|postal|zip|coupon|promo|amount|captcha|password|전화|휴대폰|이메일|우편|쿠폰|금액|자동입력|보안문자|비밀번호/i;
  const action = /verify|confirm|continue|sign[\s_-]*in|log[\s_-]*in|submit|next|인증|확인|로그인|계속|다음/i;
  const otherAction = /resend|send|request|cancel|back|pay|purchase|delete|transfer|withdraw|passkey|password|e[ -]?mail|\bqr\b|보내|전송|발송|요청|재전송|받기|취소|이전|결제|구매|삭제|송금|이체|출금|패스키|비밀번호|이메일/i;
  const marked = input => (input.getAttribute("autocomplete") || "")
    .toLowerCase().split(/\s+/).includes("one-time-code");
  const label = input => {
    const value = [...(input.labels || [])].map(item => item.textContent).join(" ")
      || input.getAttribute("aria-label") || input.getAttribute("placeholder") || "";
    return value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, "")
      .replace(/\s+/g, " ").trim().slice(0, 80);
  };
  const hint = input => [label(input), input.id, input.name].join(" ").replace(/([a-z])([A-Z])/g, "$1 $2");
  const personal = /\b(?:(?:first|last|full|real|user)[\s_-]*)?name\b|birth|\bdob\b|이름|성명|생년|생일|주민/i;
  const phoneLabel = /phone|mobile|telephone|전화|휴대폰/i;
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
  const identified = input => !personal.test(hint(input)) && (marked(input)
    || (otp.test(hint(input)) && (!unrelated.test(hint(input))
      // Code widgets often have names such as mobileAuthCode. A phone label still excludes phone entry.
      || (!phoneLabel.test(label(input))
        && /(?:phone|mobile|telephone)[\s_-]*(?:(?:auth(?:entication)?|verification|verify)[\s_-]*)?(?:code|otp)/i.test(hint(input)))
      || /인증\s*(?:번호|코드)|(?:verification|security|authentication)[\s_-]*code/i.test(label(input))))
    || (!unrelated.test(hint(input)) && input.maxLength >= 4 && input.maxLength <= 10
      && (input.inputMode === "numeric" || input.type === "tel" || input.type === "number")
      && input.form && controls(input.form).some(control => /verify|인증/i.test(controlText(control)) && isAction(control))));

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
      // Keep ambiguous controls available for the manual fallback.
      if (identified(input) || otp.test(hint(input)) || !unrelated.test(hint(input))) {
        targets.push({ inputs: [input], container: input, identified: identified(input) });
      }
    }
    // Only after approved SMS scanning, an otherwise unnamed control can be inferred when
    // all remaining controls are explicitly personal/contact fields. Never infer a name field.
    const possible = targets.filter(target => target.inputs.every(input => !personal.test(hint(input))
      && !unrelated.test(hint(input))));
    const known = targets.filter(target => target.identified);
    const focused = known.filter(target => target.inputs.includes(document.activeElement));
    let automatic = known.length === 1 ? known[0] : focused.length === 1 ? focused[0] : null;
    if (!known.length && code && possible.length === 1 && possible[0].inputs.every(input =>
      hint(input).replace(/[\s\-–—_:.*]/g, "") === "" || (input.maxLength >= 4 && input.maxLength <= 10))) {
      automatic = possible[0];
      automatic.identified = true;
    }
    const ordered = [...targets.filter(target => target.identified), ...targets.filter(target => !target.identified)];
    return { targets: ordered.slice(0, 8), automatic };
  }

  function fill(target, code, current = () => true) {
    if (!/^[A-Za-z0-9]{4,10}$/.test(code) || !/[0-9]/.test(code) || !target?.inputs?.length) return false;
    const inputs = target.inputs;
    if (!current() || inputs.some(input => !usable(input) || (input.type === "number" && !/^[0-9]+$/.test(code)))) return false;
    if (inputs.length > 1 ? inputs.length !== code.length : inputs[0].maxLength >= 0 && inputs[0].maxLength < code.length) return false;
    inputs[0].focus({ preventScroll: true });
    // Focus handlers can replace the widget or change its contents.
    if (!current() || inputs.some(input => !usable(input))) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
    // Set the entire split code first, so the last digit's handler sees a complete value.
    inputs.forEach((input, index) => setter.call(input, inputs.length === 1 ? code : code[index]));
    inputs.forEach(input => {
      input.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertReplacementText", data: input.value }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    return inputs.map(input => input.value).join("") === code;
  }

  function completionScope(target) {
    const first = target?.inputs?.[0];
    if (!first) return null;
    if (first.form) return first.form;
    const group = target?.container?.closest('[role="group"], fieldset');
    if (group) return group;
    // Formless logins often wrap the input in a label and put the button beside it.
    let parent = target?.container?.parentElement;
    for (let depth = 0; parent && parent !== first.ownerDocument.body && depth < 4; depth++, parent = parent.parentElement) {
      if (controls(parent).some(control => visible(control) && isAction(control))) {
        const otherOtp = [...parent.querySelectorAll("input")].some(input => structural(input)
          && identified(input) && !target.inputs.includes(input));
        if (!otherOtp) return parent;
        break;
      }
    }
    return target?.container?.parentElement;
  }

  async function complete(target, code, current = () => true, recover = fill) {
    const first = target?.inputs?.[0];
    const form = first?.form;
    const scope = completionScope(target);
    const sensitive = /payment|checkout|transfer|withdraw|purchase|delete|결제|송금|이체|출금|구매|삭제/i;
    const sensitiveForm = () => scope && (sensitive.test([scope.id, scope.getAttribute("name"), scope.getAttribute("aria-label")].join(" "))
      || controls(scope).some(control => sensitive.test(controlText(control)))
      || [...scope.querySelectorAll("input")].some(input => visible(input) && /amount|금액/i.test(hint(input))));
    let submitted = false, submittedWithCode = false, edited = false, retried = false, stable = 0, settled = 0;
    let active = target, nativeRecovery = false, waitingForButton = false;
    const retained = () => active.inputs.every(input => structural(input))
      && active.inputs.map(input => input.value).join("") === code;
    const changed = () => active.inputs.some((input, index) => input.isConnected && input.value !== ""
      && input.value !== (active.inputs.length === 1 ? code : code[index]));
    const observeEdit = event => {
      // The Android input connection produces a trusted input event for our one recovery write.
      if (nativeRecovery && active.inputs.includes(event.target) && (retained() || event.target.value === "")) return;
      if (event.isTrusted && scope?.contains(event.target)) edited = true;
    };
    const observeSubmit = () => {
      if (!submitted) submittedWithCode = retained();
      submitted = true;
    };
    const observeClick = event => {
      const button = event.target.closest?.('button, input[type="submit"], input[type="button"], [role="button"]');
      if (button && isAction(button)) observeSubmit();
    };
    form?.addEventListener("submit", observeSubmit, true);
    scope?.addEventListener("click", observeClick, true);
    first?.ownerDocument.addEventListener("input", observeEdit, true);
    first?.ownerDocument.addEventListener("change", observeEdit, true);
    try {
      if (!current() || !active?.inputs?.length || !/^[A-Za-z0-9]{4,10}$/.test(code) || !/[0-9]/.test(code)) {
        return { success: false, submitted: false };
      }
      if (active.inputs.some(input => input.value !== "")) return { success: false, submitted: false };
      fill(active, code, current);
      // Wait for controlled inputs to settle before clicking, and for delayed buttons to enable.
      for (let attempt = 0; attempt < 80 || (submitted && settled < 5); attempt++) {
        await new Promise(resolve => setTimeout(resolve, 100));
        if (!current() || edited) return { success: false, submitted };
        if (submitted) {
          // A click is only an attempt. Check the live widget after the site's handlers settle.
          // Do not retry or click again after an attempt, including a rejected attempt.
          if (++settled < 5) continue;
          return { success: submittedWithCode && retained(), submitted: true,
            departed: submittedWithCode && (!scope?.isConnected || active.inputs.every(input => !structural(input))) };
        }
        if (!retained()) {
          if (retried || changed() || !target.identified || !scope?.isConnected) return { success: false, submitted: false };
          // Retry only an empty, uniquely identified widget in the original form/group.
          const replacement = scan(first.ownerDocument, code).automatic;
          const sameInputs = replacement?.inputs.length === active.inputs.length
            && replacement.inputs.every((input, index) => input === active.inputs[index]);
          if (replacement && replacement.inputs.every(input => scope.contains(input))
              && (sameInputs || scope !== first.ownerDocument.body)) {
            active = replacement;
            retried = true;
            stable = 0;
            nativeRecovery = true;
            await recover(active, code, current);
          }
          continue;
        }
        if (++stable < 5) continue;
        if (!scope || !target.identified || sensitiveForm() || (form && !form.checkValidity())) break;
        const candidates = controls(scope).filter(element => visible(element) && isAction(element)
          && (!form || !element.form || element.form === form));
        const pendingButton = candidates.length === 0 || (candidates.length === 1 && (candidates[0].disabled
          || candidates[0].getAttribute("aria-disabled") === "true" || scope.getAttribute("aria-busy") === "true"));
        waitingForButton = pendingButton && (candidates.length > 0 || !!form);
        if (waitingForButton && !retried && attempt >= 9 && active.inputs.length === 1) {
          // Some controlled forms leave the DOM value intact but ignore synthetic events.
          retried = true;
          nativeRecovery = true;
          await recover(active, code, current);
          stable = 0;
          continue;
        }
        if (candidates.length === 0 && form) continue;
        if (candidates.length !== 1) break;
        const button = candidates[0];
        if (button.disabled || button.getAttribute("aria-disabled") === "true" || scope.getAttribute("aria-busy") === "true") continue;
        // click preserves custom handlers as well as normal HTML form validation.
        submittedWithCode = retained();
        submitted = true;
        button.click();
      }
      return { success: current() && !edited && retained() && !waitingForButton, submitted };
    } finally {
      form?.removeEventListener("submit", observeSubmit, true);
      scope?.removeEventListener("click", observeClick, true);
      first?.ownerDocument.removeEventListener("input", observeEdit, true);
      first?.ownerDocument.removeEventListener("change", observeEdit, true);
    }
  }

  globalThis.AirSmsFields = { scan, label: target => label(target.inputs[0]), fill, complete,
    canRecover: (target, code) => target.inputs.length === 1 && usable(target.inputs[0], false)
      && (target.inputs[0].value === "" || target.inputs[0].value === code),
    hasTarget: document => scan(document, null, true).targets.some(target => target.identified)
      || controls(document).some(control => visible(control)
        && /(?:send|request|get|resend).*(?:code|otp)|인증.*(?:받기|요청|전송|발송)|(?:보안|확인)\s*(?:번호|코드).*(?:받기|요청|전송|발송)/i.test(controlText(control))) };
})();

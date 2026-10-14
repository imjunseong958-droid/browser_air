/* Standard input fields only. No site-specific selectors or page-value collection. */
(() => {
  const marked = input => (input.getAttribute("autocomplete") || "")
    .toLowerCase().split(/\s+/).includes("one-time-code");

  const eligible = input => {
    if (!(input instanceof HTMLInputElement) || !input.isConnected || input.disabled || input.readOnly
        || input.closest("[inert]") || (input.maxLength >= 0 && input.maxLength < 4) || input.value !== "") return false;
    if (!["text", "tel", "number"].includes(input.type) && !(input.type === "password" && marked(input))) return false;
    for (let node = input; node instanceof Element; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (["hidden", "collapse"].includes(style.visibility) || style.display === "none" || style.opacity === "0") return false;
    }
    return [...input.getClientRects()].some(rect => rect.width > 0 && rect.height > 0);
  };

  const label = input => {
    const value = [...(input.labels || [])].map(item => item.textContent).join(" ")
      || input.getAttribute("aria-label") || input.getAttribute("placeholder") || "";
    return value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, "")
      .replace(/\s+/g, " ").trim().slice(0, 80);
  };

  globalThis.AirSmsFields = {
    scan(document) {
      const inputs = [...document.querySelectorAll("input")].filter(eligible);
      const standard = inputs.filter(marked);
      const ordered = [...standard, ...inputs.filter(input => !marked(input))];
      return { inputs: ordered.slice(0, 8), automatic: standard.length === 1 ? standard[0] : null };
    },
    label,
    fill(input, code) {
      if (!/^[A-Za-z0-9]{4,10}$/.test(code) || !/[0-9]/.test(code) || !eligible(input)
          || (input.maxLength >= 0 && input.maxLength < code.length)
          || (input.type === "number" && !/^[0-9]+$/.test(code))) return false;
      // The native setter and input/change events also update controlled web forms.
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
      setter.call(input, code);
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }
  };
})();

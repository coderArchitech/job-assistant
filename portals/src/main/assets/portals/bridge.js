// The page side of the JS bridge (task T3.07). Injected at document start into the portal's own origins only;
// JsBridge replaces __NAME__ and __TOKEN__. It runs before the page's scripts, so it keeps its own references
// to the channel and to JSON, and removes the channel from window where the WebView allows it.
(function (NAME, TOKEN) {
  "use strict";
  var port = window[NAME];
  if (!port || window.top !== window) return; // Main frame only; JsBridge ignores frames too.

  var stringify = JSON.stringify;
  var parse = JSON.parse;
  var defineProperty = Object.defineProperty;
  var post = port.postMessage.bind(port);
  var MAX_TEXT = 500;
  var MAX_ELEMENTS = 200;
  var MAX_EXTRACT = 20000; // A job description can be long; anything longer is cut.
  var SECRET_AUTOCOMPLETE = /(^|\s)(current-password|new-password|one-time-code)(\s|$)/;
  var secrets = new WeakSet(); // Fields the app typed a password or OTP into.

  // The top-level message has no prototype, so a page that planted a setter or toJSON on Object.prototype
  // never sees the token.
  function send(fields) {
    var message = Object.create(null);
    for (var key in fields) if (Object.prototype.hasOwnProperty.call(fields, key)) message[key] = fields[key];
    message.token = TOKEN;
    post(stringify(message));
  }

  function fail(code, detail) {
    var error = new Error(detail || code);
    error.code = code;
    throw error;
  }

  // Rendered at all, and not hidden; works even when the view has no size yet.
  function isVisible(el) {
    if (!el.getClientRects().length) return false;
    var style = getComputedStyle(el);
    return style.visibility !== "hidden" && style.display !== "none";
  }

  function find(selector) {
    try { return document.querySelectorAll(selector); } catch (e) { return fail("bad_command", "selector"); }
  }

  function firstVisible(selector) {
    var all = find(selector);
    if (!all.length) fail("not_found", selector);
    for (var i = 0; i < all.length; i++) if (isVisible(all[i])) return all[i];
    return fail("not_visible", selector);
  }

  function textOf(el, limit) {
    return String(el.innerText || el.textContent || "").replace(/\s+/g, " ").trim().slice(0, limit || MAX_TEXT);
  }

  // Attributes an extraction may read: links, sources, dates, labels and data-*; never values or tokens.
  var READABLE = /^(href|src|datetime|title|alt|aria-label|data-[a-z0-9-]+)$/;
  // Query parameters that may carry a sign-in or reset token are dropped from extracted links.
  var SENSITIVE_PARAM = /token|sig|auth|session|secret|key|code|pass|otp/i;

  function scrubbed(url) {
    try {
      var u = new URL(url, location.href);
      u.hash = "";
      Array.from(u.searchParams.keys()).forEach(function (k) { if (SENSITIVE_PARAM.test(k)) u.searchParams.delete(k); });
      return u.href;
    } catch (e) { return null; }
  }

  // A password or OTP field, even one a "show password" toggle turned into plain text.
  function isSecret(el) {
    return secrets.has(el) || (el instanceof HTMLInputElement &&
      (el.type === "password" || SECRET_AUTOCOMPLETE.test(String(el.getAttribute("autocomplete") || ""))));
  }

  function valueOf(el) {
    if (!("value" in el)) return null;
    if (isSecret(el)) return el.value ? "{{password}}" : "";
    return String(el.value).slice(0, MAX_TEXT);
  }

  // Sets a value through the element's own setter, so frameworks that track it see the change.
  function setValue(el, value) {
    var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
      : el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, "value").set.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function selectorOf(el) {
    if (el.id) return "#" + CSS.escape(el.id);
    var name = el.getAttribute("name");
    if (name) {
      var byName = el.tagName.toLowerCase() + "[name=\"" + CSS.escape(name) + "\"]";
      if (document.querySelectorAll(byName).length === 1) return byName;
    }
    var path = [];
    for (var node = el; node && node.nodeType === 1 && node !== document.documentElement; node = node.parentElement) {
      var index = 1;
      for (var sib = node.previousElementSibling; sib; sib = sib.previousElementSibling) {
        if (sib.tagName === node.tagName) index++;
      }
      path.unshift(node.tagName.toLowerCase() + ":nth-of-type(" + index + ")");
      if (node.id) { path[0] = "#" + CSS.escape(node.id); break; }
    }
    return path.join(" > ");
  }

  function labelOf(el) {
    if (el.labels && el.labels.length) return textOf(el.labels[0]);
    return String(el.getAttribute("aria-label") || el.getAttribute("placeholder") || "").slice(0, MAX_TEXT);
  }

  var commands = {
    query: function (c) {
      var all = find(c.selector);
      var el = all[0];
      var anyVisible = false;
      for (var v = 0; v < all.length && !anyVisible; v++) anyVisible = isVisible(all[v]);
      return {
        count: all.length,
        visible: anyVisible,
        text: el ? textOf(el) : null,
        value: el ? valueOf(el) : null
      };
    },

    click: function (c) {
      var el = firstVisible(c.selector);
      el.scrollIntoView({ block: "center" });
      el.click();
      return true;
    },

    type: function (c) {
      var el = firstVisible(c.selector);
      if (c.secret) secrets.add(el);
      el.focus();
      setValue(el, c.text);
      return true;
    },

    select: function (c) {
      var el = firstVisible(c.selector);
      if (!(el instanceof HTMLSelectElement)) fail("no_option", "not a select");
      for (var i = 0; i < el.options.length; i++) {
        var option = el.options[i];
        if (option.value === c.option || option.text.trim() === c.option) {
          setValue(el, option.value);
          return true;
        }
      }
      return fail("no_option", c.option);
    },

    upload: function (c) {
      var all = find(c.selector);
      var el = all[0];
      if (!el) fail("not_found", c.selector);
      if (!(el instanceof HTMLInputElement) || el.type !== "file") fail("not_file_input", c.selector);
      var binary = atob(c.data);
      var bytes = new Uint8Array(binary.length);
      for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      var transfer = new DataTransfer();
      transfer.items.add(new File([bytes], c.fileName, { type: c.mimeType }));
      el.files = transfer.files;
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    },

    waitFor: function (c) {
      var end = Date.now() + c.timeoutMs;
      return new Promise(function (resolve, reject) {
        (function poll() {
          var all = find(c.selector);
          for (var i = 0; i < all.length; i++) if (isVisible(all[i])) return resolve(true);
          if (Date.now() >= end) {
            var error = new Error(c.selector);
            error.code = "timeout";
            return reject(error);
          }
          setTimeout(poll, 100);
        })();
      });
    },

    extract: function (c) {
      function read(scope) {
        var out = {};
        for (var name in c.fields) {
          if (!Object.prototype.hasOwnProperty.call(c.fields, name)) continue;
          var spec = c.fields[name];
          var el;
          try { el = spec.selector ? scope.querySelector(spec.selector) : scope; } catch (e) { fail("bad_command", "selector"); }
          var attr = spec.attribute;
          if (!el || el.nodeType !== 1 || isSecret(el) || (attr && !READABLE.test(attr))) { out[name] = null; continue; }
          var value = !attr ? textOf(el, MAX_EXTRACT)
            : (attr === "href" || attr === "src") ? (el.getAttribute(attr) === null ? null : scrubbed(el.getAttribute(attr)))
            : el.getAttribute(attr);
          out[name] = value === null ? null : String(value).slice(0, MAX_EXTRACT);
        }
        return out;
      }
      if (!c.items) return read(document);
      var all = find(c.items);
      var rows = [];
      for (var i = 0; i < all.length && rows.length < c.max; i++) rows.push(read(all[i]));
      return rows;
    },

    snapshot: function () {
      var found = document.querySelectorAll(
        "input, textarea, select, button, a[href], [role=button], [role=link]");
      var elements = [];
      for (var i = 0; i < found.length && elements.length < MAX_ELEMENTS; i++) {
        var el = found[i];
        if (!isVisible(el) || (el instanceof HTMLInputElement && el.type === "hidden")) continue;
        elements.push({
          selector: selectorOf(el),
          tag: el.tagName.toLowerCase(),
          type: el.getAttribute("type"),
          label: labelOf(el),
          text: el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement ? null : textOf(el),
          value: valueOf(el)
        });
      }
      // No query or fragment: they can carry sign-in or reset tokens.
      return { url: location.origin + location.pathname, title: document.title, elements: elements };
    }
  };

  function onCommand(event) {
    var command;
    try { command = parse(event.data); } catch (e) { return; }
    if (!command || typeof command.id !== "number") return;
    Promise.resolve()
      .then(function () {
        var run = commands[command.cmd];
        if (!run) fail("bad_command", String(command.cmd));
        return run(command);
      })
      .then(function (value) {
        send({ type: "reply", id: command.id, ok: true, value: value === undefined ? null : value });
      }, function (error) {
        send({
          type: "reply", id: command.id, ok: false,
          code: (error && error.code) || "page_error",
          detail: String((error && error.message) || error).slice(0, 200)
        });
      });
  }

  // Hide the channel from the page. Where the WebView will not let it go, at least lock the handler so a page
  // script cannot take the commands (and the text they type) for itself.
  var hidden = false;
  try { hidden = delete window[NAME] && !(NAME in window); } catch (ignored) { hidden = false; }
  port.onmessage = onCommand; // Registers the handler with the WebView.
  try {
    defineProperty(port, "onmessage", { value: onCommand, writable: false, configurable: false });
  } catch (ignored) { /* A WebView that refuses keeps the plain handler. */ }
  if (!hidden) {
    try { defineProperty(window, NAME, { enumerable: false }); } catch (ignored) { /* Best effort. */ }
  }

  send({ type: "ready", url: location.href });
})("__NAME__", "__TOKEN__");

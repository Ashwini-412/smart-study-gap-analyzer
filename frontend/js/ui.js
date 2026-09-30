// DOM helpers. Everything is built with createElement/textContent: API-provided strings are never
// parsed as HTML, so a topic or quiz name like "<img onerror=...>" is shown as text.

/**
 * el("a", {href: "x.html", class: "btn", text: "Go", onclick: fn}, child, "text", ...)
 * Strings and numbers among the children become text nodes; null/false children are skipped.
 */
export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) {
      continue;
    }
    if (key === "class") {
      node.className = value;
    } else if (key === "text") {
      node.textContent = String(value);
    } else if (key.startsWith("on") && typeof value === "function") {
      node.addEventListener(key.slice(2), value);
    } else {
      node.setAttribute(key, value === true ? "" : String(value));
    }
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) {
      continue;
    }
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

export function clear(node) {
  node.replaceChildren();
  return node;
}

// ---- view states: every section shows exactly one of loading / empty / error / content ----

export function showLoading(container, label = "Loading…") {
  container.setAttribute("aria-busy", "true");
  clear(container).append(el("p", { class: "state state-loading", role: "status" }, el("span", { class: "spinner" }), label));
}

export function showEmpty(container, message, action = null) {
  container.removeAttribute("aria-busy");
  clear(container).append(el("div", { class: "state state-empty" }, el("p", { text: message }), action));
}

/** err may be an ApiError (status 0 = backend unreachable) or any Error. */
export function showError(container, err, retry = null) {
  container.removeAttribute("aria-busy");
  const offline = err && err.status === 0;
  clear(container).append(el("div", { class: "state state-error", role: "alert" },
      el("p", { class: "state-title", text: offline ? "Backend unavailable" : "Something went wrong" }),
      el("p", { text: err && err.message ? err.message : "Unexpected error." }),
      retry ? el("button", { type: "button", class: "btn btn-secondary", onclick: retry, text: "Try again" }) : null));
}

export function showContent(container, ...nodes) {
  container.removeAttribute("aria-busy");
  clear(container).append(...nodes);
}

// ---- inline messages for forms ----

/**
 * Shows an ApiError's message and any per-field messages (their text is fixed by the backend and
 * already names the field, e.g. "Email is required"). With a form, inputs whose id matches a field
 * are marked aria-invalid and point at the message; marks from a previous attempt are cleared.
 */
export function showFormError(container, err, form = null) {
  const fields = err && err.fields ? Object.entries(err.fields) : [];
  if (!container.id) {
    container.id = `form-error-${Math.random().toString(36).slice(2)}`;
  }
  if (form) {
    clearInvalid(form);
    for (const [field] of fields) {
      const input = form.querySelector(`#${CSS.escape(field)}`);
      if (input) {
        input.setAttribute("aria-invalid", "true");
        input.dataset.errorRef = container.id;
        input.setAttribute("aria-describedby",
            [input.getAttribute("aria-describedby"), container.id].filter(Boolean).join(" "));
      }
    }
  }
  const message = fields.length ? "Please correct the following:" : (err && err.message ? err.message : "Unexpected error.");
  clear(container).append(el("div", { class: "alert alert-error", role: "alert" },
      el("p", { text: message }),
      fields.length ? el("ul", {}, fields.map(([, msg]) => el("li", { text: msg }))) : null));
}

/** Removes the aria-invalid marks showFormError added to a form's inputs. */
export function clearInvalid(form) {
  for (const input of form.querySelectorAll("[aria-invalid]")) {
    input.removeAttribute("aria-invalid");
    const kept = (input.getAttribute("aria-describedby") || "").split(" ")
        .filter(id => id && id !== input.dataset.errorRef).join(" ");
    kept ? input.setAttribute("aria-describedby", kept) : input.removeAttribute("aria-describedby");
    delete input.dataset.errorRef;
  }
}

export function showNotice(container, message, kind = "info") {
  clear(container).append(el("div", { class: `alert alert-${kind}`, role: "status", text: message }));
}

// ---- formatting (display only: every value comes from the backend) ----

export function formatDateTime(iso) {
  if (!iso) {
    return "—";
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : date.toLocaleString();
}

export function formatPercent(value) {
  if (value === null || value === undefined) {
    return "—";
  }
  const n = Number(value);
  return Number.isFinite(n) ? `${n.toFixed(2)}%` : "—";
}

const CLASSIFICATION_CLASSES = {
  "Strong": "badge-strong",
  "Moderate": "badge-moderate",
  "Needs Improvement": "badge-weak",
  "No Data": "badge-nodata",
};

/** The backend's classification label, verbatim, styled by value (unknown labels stay neutral). */
export function classificationBadge(label) {
  return el("span", { class: `badge ${CLASSIFICATION_CLASSES[label] || "badge-nodata"}`, text: label });
}

/** Horizontal bar for a 0..100 value; null draws an empty track. Width set via CSSOM (CSP-safe). */
export function meter(value, label) {
  const fill = el("span", { class: "meter-fill" });
  const n = Number(value);
  fill.style.width = `${Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0}%`;
  return el("span", { class: "meter", role: "img", "aria-label": label }, fill);
}

/** Map of quizId -> title from GET /api/quizzes (history/results carry only quizId). */
export function quizTitles(quizzes) {
  const map = new Map();
  for (const q of quizzes || []) {
    map.set(q.id, q.title);
  }
  return map;
}

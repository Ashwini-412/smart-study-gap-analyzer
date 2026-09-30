// Central API client. Every request goes through api(), which attaches the session token,
// normalises the backend's error format ({error, fields?}) into ApiError, and turns a 401 on a
// protected call into a clean "session expired" redirect instead of a broken page.
//
// The token lives in sessionStorage (cleared when the tab closes). Passwords are never stored.

const TOKEN_KEY = "ssga.sessionToken";
const AUTH_PAGES = ["login.html", "register.html"];

export class ApiError extends Error {
  /** status 0 = the server could not be reached. fields = per-field validation messages, if any. */
  constructor(status, message, fields = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fields = fields;
  }
}

export function getToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token) {
  try {
    sessionStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Storage unavailable (private mode / blocked): the user will simply have to sign in again.
  }
}

export function clearToken() {
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // Nothing stored.
  }
}

function onAuthPage() {
  const page = location.pathname.split("/").pop();
  return AUTH_PAGES.includes(page);
}

/** Drops the session and sends the user to sign in, explaining why. */
export function sessionExpired() {
  clearToken();
  if (!onAuthPage()) {
    location.replace("login.html?expired=1");
  }
}

const DEFAULT_MESSAGES = {
  400: "The request was not valid.",
  401: "Please sign in.",
  404: "Not found.",
  405: "That action is not allowed.",
  409: "That already exists.",
  500: "The server had a problem. Please try again.",
};

/**
 * Calls the backend. Resolves with the parsed JSON body (null for an empty body) on 2xx; rejects
 * with ApiError otherwise. Protected calls (auth: true, the default) without a token, or answered
 * with 401, end the session and redirect to the login page.
 */
export async function api(method, path, body, { auth = true } = {}) {
  const headers = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (auth) {
    const token = getToken();
    if (!token) {
      sessionExpired();
      throw new ApiError(401, DEFAULT_MESSAGES[401]);
    }
    headers.Authorization = "Bearer " + token;
  }

  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Check that the backend is running and try again.");
  }

  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (response.ok) {
    return data;
  }
  if (response.status === 401 && auth) {
    sessionExpired();
    throw new ApiError(401, "Your session has expired. Please sign in again.");
  }
  const message = data && typeof data.error === "string" ? data.error : (DEFAULT_MESSAGES[response.status]
      || `Request failed (${response.status}).`);
  const fields = data && data.fields && typeof data.fields === "object" ? data.fields : null;
  throw new ApiError(response.status, message, fields);
}

/** Positive integer id from the query string, or null. Never trusted beyond building a URL. */
export function idParam(name = "id") {
  const raw = new URLSearchParams(location.search).get(name);
  return raw !== null && /^[1-9][0-9]{0,18}$/.test(raw) ? raw : null;
}

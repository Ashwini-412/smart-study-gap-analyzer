// Session guard + shared header for protected pages. Identity always comes from GET /api/auth/me:
// the frontend never sends or stores a student id.

import { api, clearToken, getToken } from "./api.js";
import { clear, el } from "./ui.js";

const NAV = [
  { href: "dashboard.html", label: "Dashboard" },
  { href: "quizzes.html", label: "Quizzes" },
  { href: "history.html", label: "History" },
];

/**
 * Resolves with the current student ({id, name, email}) and renders the header. Without a token it
 * redirects to login immediately; an invalid/expired token redirects via the API client's 401 path.
 * Other failures (e.g. backend down) reject so the page can show an error state.
 */
export async function requireAuth() {
  if (!getToken()) {
    location.replace("login.html");
    return new Promise(() => {}); // never resolves: the page is navigating away
  }
  const user = await api("GET", "/api/auth/me");
  renderHeader(user);
  return user;
}

export async function logout() {
  try {
    await api("POST", "/api/auth/logout");
  } catch {
    // Even if the server call fails (already expired, offline), forget the token locally.
  }
  clearToken();
  location.replace("login.html?loggedOut=1");
}

function renderHeader(user) {
  const header = document.getElementById("app-header");
  if (!header) {
    return;
  }
  const current = location.pathname.split("/").pop() || "dashboard.html";
  clear(header).append(
      el("a", { class: "brand", href: "dashboard.html", text: "Smart Study Gap Analyzer" }),
      el("nav", { "aria-label": "Main" },
          NAV.map(item => el("a", {
            href: item.href,
            text: item.label,
            "aria-current": item.href === current ? "page" : null,
          }))),
      el("div", { class: "user" },
          el("span", { class: "user-name", title: user.email, text: user.name }),
          el("button", { type: "button", class: "btn btn-small btn-secondary", onclick: logout, text: "Log out" })));
}

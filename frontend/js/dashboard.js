// Dashboard: composed from existing endpoints only (there is no /api/dashboard):
//   GET /api/auth/me, GET /api/attempts, GET /api/performance/gaps, GET /api/quizzes (titles).
// Counts shown here are counts of what the backend returned; nothing is scored in the browser.

import { api } from "./api.js";
import { requireAuth } from "./auth.js";
import { renderAttemptTable } from "./attempts.js";
import { classificationBadge, el, formatPercent, meter, quizTitles, showContent, showEmpty, showError,
  showLoading } from "./ui.js";

const RECENT_LIMIT = 5;

const welcome = document.getElementById("welcome");
const summary = document.getElementById("summary");
const gapsBox = document.getElementById("gaps");
const recentBox = document.getElementById("recent");

async function load() {
  showLoading(welcome, "Loading your account…");
  let user;
  try {
    user = await requireAuth();
  } catch (err) {
    showError(welcome, err, load);
    summary.replaceChildren();
    gapsBox.replaceChildren();
    recentBox.replaceChildren();
    return;
  }
  showContent(welcome, el("h1", {}, "Welcome, ", el("span", { text: user.name })),
      el("p", { class: "muted", text: "Here is how your study is going." }));

  showLoading(summary, "Loading summary…");
  showLoading(gapsBox, "Loading topic results…");
  showLoading(recentBox, "Loading attempts…");

  const [attempts, gaps, quizzes] = await Promise.allSettled([
    api("GET", "/api/attempts"),
    api("GET", "/api/performance/gaps"),
    api("GET", "/api/quizzes"),
  ]);
  const titles = quizTitles(quizzes.status === "fulfilled" ? quizzes.value : []);

  renderSummary(attempts, gaps);
  renderGaps(gaps);
  renderRecent(attempts, titles);
}

function card(label, value, detail) {
  return el("div", { class: "stat" },
      el("p", { class: "stat-label", text: label }),
      el("p", { class: "stat-value", text: value }),
      detail ? el("p", { class: "stat-detail", text: detail }) : null);
}

function renderSummary(attempts, gaps) {
  if (attempts.status === "rejected" && gaps.status === "rejected") {
    showError(summary, attempts.reason, load);
    return;
  }
  const cards = [];
  if (attempts.status === "fulfilled") {
    const list = attempts.value;
    cards.push(card("Total attempts", String(list.length)));
    cards.push(card("Latest score", list.length ? formatPercent(list[0].scorePercent) : "—",
        list.length ? null : "No attempts yet"));
  }
  if (gaps.status === "fulfilled") {
    const list = gaps.value;
    const withData = list.filter(g => g.classification !== "No Data").length;
    const weak = list.filter(g => g.classification === "Needs Improvement").length;
    const strong = list.filter(g => g.classification === "Strong").length;
    cards.push(card("Topics with data", `${withData} of ${list.length}`));
    cards.push(card("Needs improvement", String(weak), weak ? "Focus here next" : null));
    cards.push(card("Strong topics", String(strong)));
  }
  showContent(summary, ...cards);
}

function renderGaps(gaps) {
  if (gaps.status === "rejected") {
    showError(gapsBox, gaps.reason, load);
    return;
  }
  const list = gaps.value;
  if (!list.length) {
    showEmpty(gapsBox, "No topics exist yet.");
    return;
  }
  const hasData = list.some(g => g.classification !== "No Data");
  const rows = list.map(g => el("tr", {},
      el("th", { scope: "row", text: g.topicName }),
      el("td", { class: "num", text: String(g.totalQuestions) }),
      el("td", { class: "num", text: String(g.correctCount) }),
      el("td", { class: "accuracy" },
          meter(g.accuracyPercent, `Accuracy ${formatPercent(g.accuracyPercent)}`),
          el("span", { class: "num", text: formatPercent(g.accuracyPercent) })),
      el("td", {}, classificationBadge(g.classification))));
  const table = el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {},
          el("th", { scope: "col", text: "Topic" }),
          el("th", { scope: "col", class: "num", text: "Questions" }),
          el("th", { scope: "col", class: "num", text: "Correct" }),
          el("th", { scope: "col", text: "Accuracy" }),
          el("th", { scope: "col", text: "Result" }))),
      el("tbody", {}, rows)));
  showContent(gapsBox,
      hasData ? null : el("p", { class: "alert alert-info" }, "No topic history yet. ",
          el("a", { href: "quizzes.html", text: "Take a quiz" }), " to see your study gaps."),
      table);
  document.getElementById("gaps-legend").textContent =
      "Cumulative accuracy across all your attempts; unanswered questions count as incorrect.";
}

function renderRecent(attempts, titles) {
  if (attempts.status === "rejected") {
    showError(recentBox, attempts.reason, load);
    return;
  }
  const list = attempts.value;
  if (!list.length) {
    showEmpty(recentBox, "No attempts yet.", el("a", { class: "btn", href: "quizzes.html", text: "Browse quizzes" }));
    return;
  }
  showContent(recentBox, renderAttemptTable(list.slice(0, RECENT_LIMIT), titles));
}

load();

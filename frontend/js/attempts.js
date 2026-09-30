// Attempt list rendering shared by the dashboard (recent) and history page (all). Rows are shown
// in the order the backend returns them (newest first).

import { el, formatDateTime, formatPercent } from "./ui.js";

export function renderAttemptTable(attempts, titles) {
  const rows = attempts.map(a => el("tr", {},
      el("th", { scope: "row", text: titles.get(a.quizId) || `Quiz #${a.quizId}` }),
      el("td", {}, el("time", { datetime: a.submittedAt, text: formatDateTime(a.submittedAt) })),
      el("td", { class: "num", text: String(a.totalQuestions) }),
      el("td", { class: "num", text: String(a.answeredCount) }),
      el("td", { class: "num", text: String(a.correctCount) }),
      el("td", { class: "num strong", text: formatPercent(a.scorePercent) }),
      el("td", {}, el("a", { class: "link", href: `result.html?id=${encodeURIComponent(a.id)}`, text: "View" }))));
  return el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {},
          el("th", { scope: "col", text: "Quiz" }),
          el("th", { scope: "col", text: "Submitted" }),
          el("th", { scope: "col", class: "num", text: "Questions" }),
          el("th", { scope: "col", class: "num", text: "Answered" }),
          el("th", { scope: "col", class: "num", text: "Correct" }),
          el("th", { scope: "col", class: "num", text: "Score" }),
          el("th", { scope: "col" }, el("span", { class: "visually-hidden", text: "Details" })))),
      el("tbody", {}, rows)));
}

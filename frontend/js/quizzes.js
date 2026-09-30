import { api } from "./api.js";
import { requireAuth } from "./auth.js";
import { el, showContent, showEmpty, showError, showLoading } from "./ui.js";

const box = document.getElementById("quizzes");

async function load() {
  showLoading(box, "Loading quizzes…");
  try {
    await requireAuth();
    const quizzes = await api("GET", "/api/quizzes");
    if (!quizzes.length) {
      showEmpty(box, "No quizzes are available yet.");
      return;
    }
    showContent(box, el("ul", { class: "quiz-grid" }, quizzes.map(q => el("li", { class: "card quiz-card" },
        el("h2", { text: q.title }),
        q.description ? el("p", { class: "muted", text: q.description }) : null,
        q.topics.length ? el("ul", { class: "chips", "aria-label": "Topics" },
            q.topics.map(t => el("li", { class: "chip", text: t.name }))) : null,
        el("a", { class: "btn", href: `quiz.html?id=${encodeURIComponent(q.id)}`, text: "Open quiz" })))));
  } catch (err) {
    showError(box, err, load);
  }
}

load();

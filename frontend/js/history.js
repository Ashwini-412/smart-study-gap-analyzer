import { api } from "./api.js";
import { renderAttemptTable } from "./attempts.js";
import { requireAuth } from "./auth.js";
import { el, quizTitles, showContent, showEmpty, showError, showLoading } from "./ui.js";

const box = document.getElementById("history");

async function load() {
  showLoading(box, "Loading your attempts…");
  try {
    await requireAuth();
    const [attempts, quizzes] = await Promise.all([
      api("GET", "/api/attempts"),
      api("GET", "/api/quizzes").catch(() => []), // titles are a nicety; fall back to "Quiz #id"
    ]);
    if (!attempts.length) {
      showEmpty(box, "You have not taken any quizzes yet.",
          el("a", { class: "btn", href: "quizzes.html", text: "Browse quizzes" }));
      return;
    }
    showContent(box, renderAttemptTable(attempts, quizTitles(quizzes)));
  } catch (err) {
    showError(box, err, load);
  }
}

load();

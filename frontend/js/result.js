// One attempt's stored result from GET /api/attempts/{id}. The score, counts and per-question
// correctness are shown exactly as the backend evaluated them. Question/option text comes from the
// quiz-taking listing (GET /api/quizzes/{id}/questions), which has no answer key; the result only
// says whether the student's own choice was correct and never names the correct option.

import { api, ApiError, idParam } from "./api.js";
import { requireAuth } from "./auth.js";
import { el, formatDateTime, formatPercent, showContent, showError, showLoading } from "./ui.js";

const box = document.getElementById("result");
const attemptId = idParam();

async function load() {
  showLoading(box, "Loading result…");
  try {
    await requireAuth();
    if (!attemptId) {
      throw new ApiError(400, "This result link is not valid.");
    }
    const attempt = await api("GET", `/api/attempts/${attemptId}`);
    // Text lookups are best-effort: the stored result is still shown if they fail.
    const [quiz, questions] = await Promise.all([
      api("GET", `/api/quizzes/${attempt.quizId}`).catch(() => null),
      api("GET", `/api/quizzes/${attempt.quizId}/questions`).catch(() => []),
    ]);
    render(attempt, quiz, questions);
  } catch (err) {
    // The backend answers 404 both for a missing attempt and for another student's attempt.
    showError(box, err.status === 404 ? new ApiError(404, "This attempt was not found.") : err, load);
  }
}

function render(attempt, quiz, questions) {
  const byId = new Map(questions.map(q => [q.id, q]));
  const title = quiz ? quiz.title : `Quiz #${attempt.quizId}`;
  document.title = `Result: ${title} · Smart Study Gap Analyzer`;

  const summary = el("section", { class: "card result-summary" },
      el("h1", { text: title }),
      el("p", { class: "score", text: formatPercent(attempt.scorePercent) }),
      el("dl", { class: "facts" },
          el("dt", { text: "Correct" }), el("dd", { text: `${attempt.correctCount} of ${attempt.totalQuestions}` }),
          el("dt", { text: "Answered" }), el("dd", { text: `${attempt.answeredCount} of ${attempt.totalQuestions}` }),
          el("dt", { text: "Submitted" }), el("dd", { text: formatDateTime(attempt.submittedAt) })));

  const items = attempt.answers.map((a, index) => {
    const question = byId.get(a.questionId);
    const selected = question && a.selectedOptionId !== null
        ? question.options.find(o => o.id === a.selectedOptionId) : null;
    const yourAnswer = a.selectedOptionId === null ? "Not answered"
        : selected ? selected.text : `Option #${a.selectedOptionId}`;
    return el("li", { class: `card answer ${a.correct ? "answer-correct" : "answer-incorrect"}` },
        el("div", { class: "answer-head" },
            el("span", { class: "q-number", text: `Question ${index + 1}` }),
            el("span", { class: `badge ${a.correct ? "badge-strong" : "badge-weak"}`, text: a.correct ? "Correct" : "Incorrect" })),
        el("p", { class: "answer-question", text: question ? question.questionText : `Question #${a.questionId}` }),
        el("p", { class: "muted" }, "Your answer: ", el("span", { class: "your-answer", text: yourAnswer })));
  });

  showContent(box, summary,
      el("h2", { text: "Questions" }),
      el("ol", { class: "answers" }, items),
      el("div", { class: "form-actions" },
          el("a", { class: "btn", href: `quiz.html?id=${encodeURIComponent(attempt.quizId)}`, text: "Retake quiz" }),
          el("a", { class: "btn btn-secondary", href: "dashboard.html", text: "Back to dashboard" })));
}

load();

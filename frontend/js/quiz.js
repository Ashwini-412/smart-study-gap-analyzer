// Take a quiz. Questions come from GET /api/quizzes/{id}/questions, which carries no answer key,
// so the browser never knows which option is correct and never grades anything. Submission sends
// only {questionId, selectedOptionId} pairs; evaluation happens on the server.

import { api, ApiError, idParam } from "./api.js";
import { requireAuth } from "./auth.js";
import { clear, el, formatDateTime, showContent, showError, showEmpty, showFormError, showLoading } from "./ui.js";

const box = document.getElementById("quiz");
const quizId = idParam();

async function load() {
  showLoading(box, "Loading quiz…");
  try {
    await requireAuth();
    if (!quizId) {
      throw new ApiError(400, "This quiz link is not valid.");
    }
    const [quiz, questions] = await Promise.all([
      api("GET", `/api/quizzes/${quizId}`),
      api("GET", `/api/quizzes/${quizId}/questions`),
    ]);
    document.title = `${quiz.title} · Smart Study Gap Analyzer`;
    render(quiz, questions);
  } catch (err) {
    showError(box, err.status === 404 ? new ApiError(404, "This quiz does not exist.") : err, load);
  }
}

function render(quiz, questions) {
  const heading = el("div", { class: "quiz-head" },
      el("h1", { text: quiz.title }),
      quiz.description ? el("p", { class: "muted", text: quiz.description }) : null);
  if (!questions.length) {
    const empty = el("div", { class: "card" });
    showEmpty(empty, "This quiz has no questions yet.");
    showContent(box, heading, empty);
    return;
  }

  const progress = el("p", { class: "muted", role: "status" });
  const formError = el("div");
  const submit = el("button", { type: "submit", class: "btn", text: "Submit answers" });
  const fieldsets = questions.map(q => el("fieldset", { class: "card question" },
      el("legend", {}, el("span", { class: "q-number", text: `Question ${q.position}` }), el("span", { text: q.questionText })),
      q.options.map(o => el("label", { class: "option" },
          el("input", { type: "radio", name: `q-${q.id}`, value: String(o.id), "data-question": String(q.id) }),
          el("span", { text: o.text })))));
  const form = el("form", { novalidate: true }, fieldsets, formError,
      el("div", { class: "form-actions" }, progress, submit));

  const updateProgress = () => {
    const answered = form.querySelectorAll("input[type=radio]:checked").length;
    progress.textContent = answered === questions.length
        ? `All ${questions.length} questions answered.`
        : `${answered} of ${questions.length} answered. Unanswered questions count as incorrect.`;
  };
  form.addEventListener("change", updateProgress);
  updateProgress();

  form.addEventListener("submit", async event => {
    event.preventDefault();
    clear(formError);
    const answers = [...form.querySelectorAll("input[type=radio]:checked")].map(input => ({
      questionId: Number(input.dataset.question),
      selectedOptionId: Number(input.value),
    }));
    if (!answers.length) {
      showFormError(formError, new ApiError(400, "Select an answer for at least one question."));
      return;
    }
    submit.disabled = true;
    submit.textContent = "Submitting…";
    try {
      const result = await api("POST", `/api/quizzes/${quizId}/attempts`, { answers });
      showSubmitted(quiz, result);
    } catch (err) {
      showFormError(formError, err);
      submit.disabled = false;
      submit.textContent = "Submit answers";
    }
  });

  showContent(box, heading, form);
}

/** Shows exactly what POST /api/quizzes/{id}/attempts returned (no score: that is on the result page). */
function showSubmitted(quiz, result) {
  showContent(box, el("section", { class: "card submitted", role: "status" },
      el("h1", { text: "Answers submitted" }),
      el("p", {}, el("span", { text: quiz.title })),
      el("dl", { class: "facts" },
          el("dt", { text: "Answered" }), el("dd", { text: `${result.answeredCount} of ${result.totalQuestions}` }),
          el("dt", { text: "Submitted" }), el("dd", { text: formatDateTime(result.submittedAt) })),
      el("div", { class: "form-actions" },
          el("a", { class: "btn", href: `result.html?id=${encodeURIComponent(result.id)}`, text: "View result" }),
          el("a", { class: "btn btn-secondary", href: "dashboard.html", text: "Back to dashboard" }))));
}

load();

import { api, ApiError, getToken, setToken } from "./api.js";
import { clear, showFormError, showNotice } from "./ui.js";

const form = document.getElementById("login-form");
const email = document.getElementById("email");
const password = document.getElementById("password");
const submit = document.getElementById("submit");
const formError = document.getElementById("form-error");
const notice = document.getElementById("notice");

const params = new URLSearchParams(location.search);
if (params.has("expired")) {
  showNotice(notice, "Your session has expired. Please sign in again.", "warning");
} else if (params.has("loggedOut")) {
  showNotice(notice, "You have been signed out.");
} else if (params.has("registered")) {
  showNotice(notice, "Account created. Please sign in.", "success");
} else if (getToken()) {
  location.replace("dashboard.html"); // already signed in (the dashboard re-validates the token)
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  clear(formError);
  submit.disabled = true;
  try {
    const result = await api("POST", "/api/auth/login",
        { email: email.value, password: password.value }, { auth: false });
    setToken(result.token);
    password.value = "";
    location.replace("dashboard.html");
  } catch (err) {
    password.value = ""; // never keep the password around after a failed attempt
    if (err instanceof ApiError && err.status === 401) {
      showFormError(formError, new ApiError(401, "Invalid email or password."));
    } else {
      showFormError(formError, err);
    }
    password.focus();
  } finally {
    submit.disabled = false;
  }
});

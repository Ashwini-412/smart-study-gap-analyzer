import { api, ApiError } from "./api.js";
import { clear, showFormError } from "./ui.js";

const form = document.getElementById("register-form");
const nameInput = document.getElementById("name");
const email = document.getElementById("email");
const password = document.getElementById("password");
const confirm = document.getElementById("confirm");
const submit = document.getElementById("submit");
const formError = document.getElementById("form-error");

form.addEventListener("submit", async event => {
  event.preventDefault();
  clear(formError);
  // A typo check only; the password policy itself is enforced by the backend.
  if (password.value !== confirm.value) {
    showFormError(formError, new ApiError(400, "The passwords do not match."));
    return;
  }
  submit.disabled = true;
  try {
    await api("POST", "/api/auth/register",
        { name: nameInput.value, email: email.value, password: password.value }, { auth: false });
    password.value = "";
    confirm.value = "";
    location.replace("login.html?registered=1");
  } catch (err) {
    password.value = "";
    confirm.value = "";
    showFormError(formError, err);
  } finally {
    submit.disabled = false;
  }
});

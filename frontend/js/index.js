import { getToken } from "./api.js";

// The dashboard validates the token itself (GET /api/auth/me) and falls back to login on 401.
location.replace(getToken() ? "dashboard.html" : "login.html");

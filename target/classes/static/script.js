"use strict";

let sessionToken = null;
let currentStudent = null;
let facultyList = [];
let feedbackEntries = [];
let feedbackSummary = [];
let summaryOverallAverage = null;

const loginScreen = document.querySelector("#login-screen");
const appShell = document.querySelector("#app-shell");
const loginForm = document.querySelector("#login-form");
const feedbackForm = document.querySelector("#feedback-form");
const sidebar = document.querySelector("#sidebar");
const overlay = document.querySelector("#sidebar-overlay");
const viewTitles = {
    dashboard: "Dashboard",
    feedback: "Submit feedback",
    summary: "Feedback summary",
    reports: "Feedback reports"
};

async function apiRequest(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (sessionToken) headers.set("Authorization", `Bearer ${sessionToken}`);
    if (options.body) headers.set("Content-Type", "application/json");

    const response = await fetch(path, { ...options, headers });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(result.error || `Request failed (${response.status}).`);
    }
    return result;
}

function average(entry) {
    return (Number(entry.teaching) + Number(entry.communication) + Number(entry.punctuality)) / 3;
}

function formatAverage(value) {
    return value === null || value === undefined ? "—" : Number(value).toFixed(1);
}

function formatDate(value) {
    const dateText = String(value).replace(" ", "T");
    const date = new Date(dateText);
    if (Number.isNaN(date.getTime())) return escapeHtml(value);
    return new Intl.DateTimeFormat("en", { day: "numeric", month: "short", year: "numeric" }).format(date);
}

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (character) => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[character]);
}

async function loadFaculty() {
    const result = await apiRequest("/api/faculty");
    facultyList = result.faculty;
    const select = document.querySelector("#faculty-select");
    select.replaceChildren(new Option("Select a faculty member", ""));
    facultyList.forEach((faculty) => {
        select.add(new Option(`${faculty.name} · ${faculty.department}`, String(faculty.id)));
    });
}

async function loadSummary() {
    const result = await apiRequest("/api/summary");
    feedbackSummary = result.summary;
    summaryOverallAverage = result.overallAverage;
    renderSummary();
}

async function loadReports() {
    const result = await apiRequest("/api/reports");
    feedbackEntries = result.reports;
    renderReport();
}

async function loadPortalData() {
    await Promise.all([loadFaculty(), loadSummary(), loadReports()]);
    renderDashboard();
}

function renderDashboard() {
    const ownFeedback = currentStudent
        ? feedbackEntries.filter((entry) => entry.studentId === currentStudent.studentId)
        : [];
    const reviewedFaculty = new Set(ownFeedback.map((entry) => entry.facultyId));
    const averageRating = ownFeedback.length
        ? ownFeedback.reduce((total, entry) => total + average(entry), 0) / ownFeedback.length
        : null;

    document.querySelector("#stat-submissions").textContent = String(ownFeedback.length);
    document.querySelector("#stat-faculty").textContent = String(reviewedFaculty.size);
    document.querySelector("#stat-rating").textContent = formatAverage(averageRating);
    document.querySelector("#summary-count").textContent = String(
        feedbackSummary.reduce((total, item) => total + item.feedbackCount, 0)
    );
    document.querySelector("#summary-faculty").textContent = String(
        feedbackSummary.filter((item) => item.feedbackCount > 0).length
    );
    document.querySelector("#summary-average").textContent = formatAverage(summaryOverallAverage);

    const recent = ownFeedback.slice(0, 3);
    document.querySelector("#recent-feedback-body").innerHTML = recent.length
        ? recent.map((entry) => `
            <tr>
                <td>${escapeHtml(entry.facultyName)}</td>
                <td>${formatDate(entry.submittedAt)}</td>
                <td><span class="rating-badge"><span aria-hidden="true">★</span>${formatAverage(average(entry))}</span></td>
                <td><span class="status-badge">Submitted</span></td>
            </tr>`).join("")
        : '<tr><td colspan="4">No feedback submitted yet.</td></tr>';
}

function renderSummary() {
    document.querySelector("#summary-body").innerHTML = feedbackSummary.map((item) => `
        <tr>
            <td>${escapeHtml(item.name)}<span class="table-subtext">${escapeHtml(item.department)}</span></td>
            <td>${item.feedbackCount}</td>
            <td>${formatAverage(item.teachingAverage)}</td>
            <td>${formatAverage(item.communicationAverage)}</td>
            <td>${formatAverage(item.punctualityAverage)}</td>
            <td class="avg-cell">${formatAverage(item.overallAverage)}</td>
        </tr>`).join("");
}

function renderReport() {
    const sorted = [...feedbackEntries];
    document.querySelector("#report-count").textContent = `${sorted.length} ${sorted.length === 1 ? "entry" : "entries"}`;
    document.querySelector("#empty-report").hidden = sorted.length > 0;
    document.querySelector("#report-body").innerHTML = sorted.map((entry) => `
        <tr>
            <td>${formatDate(entry.submittedAt)}</td>
            <td>${escapeHtml(entry.facultyName)}</td>
            <td>${entry.teaching}</td>
            <td>${entry.communication}</td>
            <td>${entry.punctuality}</td>
            <td class="avg-cell">${formatAverage(entry.overallRating)}</td>
            <td class="comment-cell">${escapeHtml(entry.comments || "—")}</td>
        </tr>`).join("");
}

function showView(viewName) {
    if (!viewTitles[viewName]) return;

    document.querySelectorAll(".view-section").forEach((section) => {
        section.hidden = section.id !== `view-${viewName}`;
    });
    document.querySelectorAll(".nav-link").forEach((button) => {
        const active = button.dataset.view === viewName;
        button.classList.toggle("active", active);
        if (active) button.setAttribute("aria-current", "page");
        else button.removeAttribute("aria-current");
    });
    document.querySelector("#current-section").textContent = viewTitles[viewName];
    closeSidebar();
    window.scrollTo({ top: 0, behavior: "smooth" });
}

async function navigateTo(viewName) {
    showView(viewName);
    const error = document.querySelector("#app-error");
    error.hidden = true;
    try {
        if (viewName === "summary") await loadSummary();
        if (viewName === "reports") {
            await loadReports();
            renderDashboard();
        }
    } catch (requestError) {
        error.textContent = requestError.message;
        error.hidden = false;
    }
}

function openApp() {
    loginScreen.hidden = true;
    appShell.hidden = false;
    document.querySelector("#user-name").textContent = currentStudent.name;
    document.querySelector("#user-avatar").textContent = currentStudent.name.charAt(0);
    document.querySelector("#greeting-name").textContent = currentStudent.name.split(" ")[0];
    renderDashboard();
    showView("dashboard");
}

function closeSidebar() {
    sidebar.classList.remove("open");
    overlay.hidden = true;
    document.querySelector("#menu-button").setAttribute("aria-expanded", "false");
}

loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const formData = new FormData(loginForm);
    const loginError = document.querySelector("#login-error");
    loginError.hidden = true;

    try {
        const result = await apiRequest("/api/login", {
            method: "POST",
            body: JSON.stringify({
                studentId: formData.get("studentId").trim(),
                password: formData.get("password")
            })
        });
        sessionToken = result.token;
        currentStudent = { studentId: result.studentId, name: result.name };
        await loadPortalData();
        openApp();
    } catch (requestError) {
        sessionToken = null;
        currentStudent = null;
        loginError.textContent = requestError.message;
        loginError.hidden = false;
    }
});

document.querySelectorAll("[data-view]").forEach((button) => {
    button.addEventListener("click", () => { void navigateTo(button.dataset.view); });
});

document.querySelectorAll("[data-view-link], [data-go]").forEach((button) => {
    button.addEventListener("click", (event) => {
        event.preventDefault();
        void navigateTo(button.dataset.viewLink || button.dataset.go);
    });
});

document.querySelector("#logout-button").addEventListener("click", async () => {
    try {
        await apiRequest("/api/logout", { method: "POST" });
    } catch (requestError) {
        console.error("Could not close the server session:", requestError);
    }
    sessionToken = null;
    currentStudent = null;
    feedbackEntries = [];
    feedbackSummary = [];
    summaryOverallAverage = null;
    facultyList = [];
    appShell.hidden = true;
    loginScreen.hidden = false;
    loginForm.reset();
    document.querySelector("#login-error").hidden = true;
});

document.querySelector("#menu-button").addEventListener("click", () => {
    sidebar.classList.add("open");
    overlay.hidden = false;
    document.querySelector("#menu-button").setAttribute("aria-expanded", "true");
});
overlay.addEventListener("click", closeSidebar);

const commentField = document.querySelector("#feedback-comment");
commentField.addEventListener("input", () => {
    document.querySelector("#comment-count").textContent = `${commentField.value.length} / 500`;
});

feedbackForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!feedbackForm.reportValidity()) return;

    const data = new FormData(feedbackForm);
    const successMessage = document.querySelector("#feedback-success");
    const errorMessage = document.querySelector("#feedback-error");
    const submitButton = feedbackForm.querySelector('button[type="submit"]');
    successMessage.hidden = true;
    errorMessage.hidden = true;
    submitButton.disabled = true;

    try {
        await apiRequest("/api/feedback", {
            method: "POST",
            body: JSON.stringify({
                facultyId: Number(data.get("faculty")),
                teaching: Number(data.get("teaching")),
                communication: Number(data.get("communication")),
                punctuality: Number(data.get("punctuality")),
                comments: data.get("comment").trim()
            })
        });
        feedbackForm.reset();
        document.querySelector("#comment-count").textContent = "0 / 500";
        successMessage.hidden = false;
        try {
            await Promise.all([loadSummary(), loadReports()]);
            renderDashboard();
        } catch (requestError) {
            const appError = document.querySelector("#app-error");
            appError.textContent = `Feedback was saved, but the latest summary could not be loaded: ${requestError.message}`;
            appError.hidden = false;
        }
    } catch (requestError) {
        errorMessage.textContent = requestError.message;
        errorMessage.hidden = false;
    } finally {
        submitButton.disabled = false;
    }
});

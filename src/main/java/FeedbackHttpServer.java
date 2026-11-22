import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

final class FeedbackHttpServer {
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final Gson GSON = new Gson();
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();

    private FeedbackHttpServer() {
    }

    static void start() throws IOException {
        int port = configuredPort();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/login", FeedbackHttpServer::handleLogin);
        server.createContext("/api/logout", FeedbackHttpServer::handleLogout);
        server.createContext("/api/faculty", FeedbackHttpServer::handleFaculty);
        server.createContext("/api/feedback", FeedbackHttpServer::handleFeedback);
        server.createContext("/api/summary", FeedbackHttpServer::handleSummary);
        server.createContext("/api/reports", FeedbackHttpServer::handleReports);
        server.createContext("/", FeedbackHttpServer::handleStaticFile);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Student Feedback System web app is listening on 0.0.0.0:" + port);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static int configuredPort() throws IOException {
        String configuredPort = System.getenv("PORT");
        if (configuredPort == null || configuredPort.trim().isEmpty()) {
            return 8080;
        }

        try {
            int port = Integer.parseInt(configuredPort.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("outside valid port range");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IOException("PORT must be a number from 1 to 65535.", exception);
        }
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST", "/api/login")) {
            return;
        }
        try {
            JsonObject request = readJson(exchange);
            String studentId = requiredString(request, "studentId").trim();
            String password = requiredString(request, "password");
            String query = "SELECT student_id, name FROM Students WHERE student_id = ? AND password = ?";

            try (Connection connection = StudentFeedbackSystem.connect();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, studentId);
                statement.setString(2, password);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        sendError(exchange, 401, "Student ID or password is incorrect.");
                        return;
                    }

                    String token = UUID.randomUUID().toString();
                    SESSIONS.put(token, result.getString("student_id"));
                    sendJson(exchange, 200, object(
                            "token", token,
                            "studentId", result.getString("student_id"),
                            "name", result.getString("name")));
                }
            }
        } catch (HttpApiException exception) {
            sendError(exchange, exception.status, exception.getMessage());
        } catch (JsonParseException | IllegalArgumentException exception) {
            sendError(exchange, 400, "Invalid JSON request: " + exception.getMessage());
        } catch (SQLException exception) {
            reportDatabaseError(exception);
            sendError(exchange, 500, "Could not check the student login.");
        }
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST", "/api/logout")) {
            return;
        }
        String token = requireToken(exchange);
        if (token == null) {
            return;
        }
        SESSIONS.remove(token);
        sendJson(exchange, 200, object("success", true));
    }

    private static void handleFaculty(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET", "/api/faculty")) {
            return;
        }
        if (requireStudent(exchange) == null) {
            return;
        }

        String query = "SELECT faculty_id, name, department FROM Faculty ORDER BY name";
        try (Connection connection = StudentFeedbackSystem.connect();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            List<Map<String, Object>> faculty = new ArrayList<>();
            while (result.next()) {
                faculty.add(object(
                        "id", result.getInt("faculty_id"),
                        "name", result.getString("name"),
                        "department", result.getString("department")));
            }
            sendJson(exchange, 200, object("faculty", faculty));
        } catch (SQLException exception) {
            reportDatabaseError(exception);
            sendError(exchange, 500, "Could not load faculty.");
        }
    }

    private static void handleFeedback(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST", "/api/feedback")) {
            return;
        }
        String studentId = requireStudent(exchange);
        if (studentId == null) {
            return;
        }

        try {
            JsonObject request = readJson(exchange);
            int facultyId = requiredInteger(request, "facultyId");
            int teaching = requiredInteger(request, "teaching");
            int communication = requiredInteger(request, "communication");
            int punctuality = requiredInteger(request, "punctuality");
            String comments = requiredString(request, "comments").trim();

            if (facultyId < 1) {
                throw new HttpApiException(400, "Choose a valid faculty member.");
            }
            validateRating(teaching, "Teaching");
            validateRating(communication, "Communication");
            validateRating(punctuality, "Punctuality");
            if (comments.length() > 500) {
                throw new HttpApiException(400, "Comments must be 500 characters or fewer.");
            }

            String insert = "INSERT INTO Feedback " +
                    "(student_id, faculty_id, teaching_rating, communication_rating, punctuality_rating, comments) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection connection = StudentFeedbackSystem.connect();
                 PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, studentId);
                statement.setInt(2, facultyId);
                statement.setInt(3, teaching);
                statement.setInt(4, communication);
                statement.setInt(5, punctuality);
                statement.setString(6, comments);
                statement.executeUpdate();
            }
            sendJson(exchange, 201, object("success", true, "message", "Feedback submitted."));
        } catch (HttpApiException exception) {
            sendError(exchange, exception.status, exception.getMessage());
        } catch (JsonParseException | IllegalArgumentException exception) {
            sendError(exchange, 400, "Invalid feedback request: " + exception.getMessage());
        } catch (SQLException exception) {
            reportDatabaseError(exception);
            sendError(exchange, 500, "Could not save feedback. Check the selected faculty and try again.");
        }
    }

    private static void handleSummary(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET", "/api/summary")) {
            return;
        }
        if (requireStudent(exchange) == null) {
            return;
        }

        String query =
                "SELECT f.faculty_id, f.name, f.department, COUNT(b.feedback_id) AS feedback_count, " +
                "ROUND(AVG(b.teaching_rating), 2) AS teaching_average, " +
                "ROUND(AVG(b.communication_rating), 2) AS communication_average, " +
                "ROUND(AVG(b.punctuality_rating), 2) AS punctuality_average, " +
                "ROUND(AVG((b.teaching_rating + b.communication_rating + b.punctuality_rating) / 3.0), 2) " +
                "AS overall_average " +
                "FROM Faculty f LEFT JOIN Feedback b ON f.faculty_id = b.faculty_id " +
                "GROUP BY f.faculty_id, f.name, f.department ORDER BY f.name";
        try (Connection connection = StudentFeedbackSystem.connect()) {
            List<Map<String, Object>> summary = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    summary.add(object(
                            "facultyId", result.getInt("faculty_id"),
                            "name", result.getString("name"),
                            "department", result.getString("department"),
                            "feedbackCount", result.getInt("feedback_count"),
                            "teachingAverage", nullableDouble(result, "teaching_average"),
                            "communicationAverage", nullableDouble(result, "communication_average"),
                            "punctualityAverage", nullableDouble(result, "punctuality_average"),
                            "overallAverage", nullableDouble(result, "overall_average")));
                }
            }
            Double overallAverage;
            try (PreparedStatement averageStatement = connection.prepareStatement(
                    "SELECT ROUND(AVG((teaching_rating + communication_rating + punctuality_rating) / 3.0), 2) " +
                    "AS overall_average FROM Feedback");
                 ResultSet averageResult = averageStatement.executeQuery()) {
                overallAverage = averageResult.next() ? nullableDouble(averageResult, "overall_average") : null;
            }
            sendJson(exchange, 200, object("summary", summary, "overallAverage", overallAverage));
        } catch (SQLException exception) {
            reportDatabaseError(exception);
            sendError(exchange, 500, "Could not load the feedback summary.");
        }
    }

    private static void handleReports(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET", "/api/reports")) {
            return;
        }
        String studentId = requireStudent(exchange);
        if (studentId == null) {
            return;
        }

        String query =
                "SELECT b.feedback_id, b.submitted_at, b.student_id, s.name AS student_name, " +
                "f.faculty_id, f.name AS faculty_name, b.teaching_rating, b.communication_rating, " +
                "b.punctuality_rating, " +
                "ROUND((b.teaching_rating + b.communication_rating + b.punctuality_rating) / 3.0, 2) " +
                "AS overall_rating, b.comments " +
                "FROM Feedback b JOIN Students s ON b.student_id = s.student_id " +
                "JOIN Faculty f ON b.faculty_id = f.faculty_id " +
                "WHERE b.student_id = ? " +
                "ORDER BY b.submitted_at DESC, b.feedback_id DESC";
        try (Connection connection = StudentFeedbackSystem.connect();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, studentId);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> reports = new ArrayList<>();
                while (result.next()) {
                    reports.add(object(
                            "feedbackId", result.getInt("feedback_id"),
                            "submittedAt", result.getString("submitted_at"),
                            "studentId", result.getString("student_id"),
                            "studentName", result.getString("student_name"),
                            "facultyId", result.getInt("faculty_id"),
                            "facultyName", result.getString("faculty_name"),
                            "teaching", result.getInt("teaching_rating"),
                            "communication", result.getInt("communication_rating"),
                            "punctuality", result.getInt("punctuality_rating"),
                            "overallRating", result.getDouble("overall_rating"),
                            "comments", result.getString("comments")));
                }
                sendJson(exchange, 200, object("reports", reports));
            }
        } catch (SQLException exception) {
            reportDatabaseError(exception);
            sendError(exchange, 500, "Could not load feedback reports.");
        }
    }

    private static void handleStaticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Only GET is allowed.");
            exchange.getResponseHeaders().set("Allow", "GET");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String fileName;
        String contentType;
        if ("/".equals(path) || "/index.html".equals(path)) {
            fileName = "index.html";
            contentType = "text/html; charset=utf-8";
        } else if ("/style.css".equals(path)) {
            fileName = "style.css";
            contentType = "text/css; charset=utf-8";
        } else if ("/script.js".equals(path)) {
            fileName = "script.js";
            contentType = "text/javascript; charset=utf-8";
        } else {
            sendError(exchange, 404, "File not found.");
            return;
        }

        try (InputStream resource = FeedbackHttpServer.class.getClassLoader()
                .getResourceAsStream("static/" + fileName)) {
            if (resource == null) {
                sendError(exchange, 404, "Frontend file is missing from the application build.");
                return;
            }
            byte[] content = resource.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        } catch (IOException exception) {
            exchange.close();
            throw exception;
        }
    }

    private static String requireStudent(HttpExchange exchange) throws IOException {
        String token = requireToken(exchange);
        return token == null ? null : SESSIONS.get(token);
    }

    private static String requireToken(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            sendError(exchange, 401, "Please log in to continue.");
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        String studentId = SESSIONS.get(token);
        if (studentId == null) {
            sendError(exchange, 401, "Your session has expired. Please log in again.");
            return null;
        }
        return token;
    }

    private static boolean requireMethod(HttpExchange exchange, String method, String path) throws IOException {
        if (!path.equals(exchange.getRequestURI().getPath())) {
            sendError(exchange, 404, "Endpoint not found.");
            return false;
        }
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            sendError(exchange, 405, "Use " + method + " for this endpoint.");
            return false;
        }
        return true;
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new HttpApiException(415, "Content-Type must be application/json.");
        }

        try (InputStream body = exchange.getRequestBody()) {
            byte[] bytes = body.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                throw new HttpApiException(413, "Request body is too large.");
            }
            JsonElement parsed = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new HttpApiException(400, "A JSON object is required.");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new HttpApiException(400, "Malformed JSON request.");
        }
    }

    private static String requiredString(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string.");
        }
        return value.getAsString();
    }

    private static int requiredInteger(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer.");
        }
        double number = value.getAsDouble();
        int integer = value.getAsInt();
        if (!Double.isFinite(number) || number != integer) {
            throw new IllegalArgumentException(name + " must be an integer.");
        }
        return integer;
    }

    private static void validateRating(int rating, String label) {
        if (rating < 1 || rating > 5) {
            throw new HttpApiException(400, label + " rating must be between 1 and 5.");
        }
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static Map<String, Object> object(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, object("error", message));
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] response = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void reportDatabaseError(SQLException exception) {
        System.err.println("Student Feedback System database error: " + exception.getMessage());
        exception.printStackTrace(System.err);
    }

    private static final class HttpApiException extends RuntimeException {
        private final int status;

        HttpApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

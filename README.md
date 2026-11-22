# Student Feedback System

A Java Swing and browser-based student feedback application. Both interfaces use the same Java JDBC code and SQLite database. The database and tables are created automatically at startup. The web interface is served by Java's built-in HTTP server.

## Requirements

- Java 11 or newer
- Apache Maven
- Docker Desktop (for the Render deployment build)

## Run the web frontend

From the project folder, start the built-in Java HTTP server:

```powershell
mvn clean compile exec:java "-Dexec.args=--server"
```

Open `http://127.0.0.1:8080` in a browser. The server uses `PORT` when set and otherwise defaults to `8080`. It binds to all network interfaces for hosting compatibility. Keep the terminal open while using the app; press Ctrl+C to stop the server. The first run downloads the SQLite JDBC and Gson JSON libraries.

The existing Swing application remains available with:

```powershell
mvn clean compile exec:java
```

The SQLite file, `student_feedback.db`, is created in the project folder when the application starts.

## Demo login

- Student ID: `STU001`
- Password: `student123`

Other seeded accounts are `STU002` and `STU003`, each with password `student123`.

The web frontend uses JSON `fetch()` requests to the Java HTTP endpoints. Login and feedback are processed by Java; faculty, summaries, and reports are read from SQLite through JDBC. Reports show the signed-in student's own submissions, while the summary calculates faculty averages across saved feedback.

## Build and run the deployment container locally

From the project folder, build and start the same Docker image used for deployment:

```powershell
docker build -t student-feedback-system .
docker run --rm -p 10000:10000 -e PORT=10000 student-feedback-system
```

Open `http://localhost:10000`. Stop the container with Ctrl+C.

## Deploy on Render

Create a **Web Service** from this repository and select the **Docker** runtime. Render will build the root `Dockerfile` and provide `PORT` to the app. No separate build or start command is needed.

SQLite data is stored in the container's local filesystem. Render Free services do not provide persistent disks, so feedback can be lost when the service restarts or is redeployed. The app remains suitable for a demo, but durable online feedback storage requires a persistent storage option.

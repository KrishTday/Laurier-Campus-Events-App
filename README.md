# 🎓 Laurier Campus Events App

A full-stack events tracker for Wilfrid Laurier students. Surfaces campus events and RSVP deadlines in one place instead of scattered club emails. Students browse events by category or search, see how far off an RSVP deadline is at a glance, and track the ones they care about, with no account required.

<img width="1050" height="1079" alt="Image" src="https://github.com/user-attachments/assets/e61d579b-4187-4141-9c2c-f38a71ae29b3" />

## 🛠️ Technologies

- Java (JDK's built-in `HttpServer`, no framework)
- SQLite via JDBC
- TypeScript (compiled directly to browser JS, no bundler)
- CSS
- HTML
- Docker
- Render (backend hosting)
- Vercel (frontend hosting)

## 🎉 Features

Here's what you can do with the Laurier Campus Events App:

- **Browse and Search**: Filter events by category or search across title, description, and location.
- **RSVP Countdowns**: See how much time is left before an RSVP deadline, with a red highlight once it's within 48 hours.
- **Track Events**: Save the events you care about to a "my tracked events" view, persisted per browser with no login needed.
- **Organizer Mode**: Add or remove events through the same REST API the UI uses, standing in for a real event-management workflow.

## 🐢 The Process

I built the backend in plain Java using the JDK's built-in `HttpServer` instead of a framework, so the whole backend only needed the JDK plus a single SQLite JDBC driver jar. I wrote a small hand-rolled JSON reader and writer to keep dependencies minimal, then set up SQLite as the database so the app had no external database service to manage.

On the frontend, I used TypeScript compiled straight to browser JavaScript with no bundler and no client-side framework, updating the DOM directly from `main.ts`. I built the category filters and search first, then layered in the RSVP countdown logic and the red 48-hour warning state. Once the core browsing experience worked, I added per-browser event tracking using a random id stored in `localStorage`, and built out an organizer mode that hits the same REST API as the rest of the app. For deployment, I containerized the backend with Docker for Render and configured the frontend to deploy separately on Vercel, pointed at the Render backend's URL.

## 📚 What I Learned

This project taught me a lot about building a backend without leaning on a framework. Working directly with Java's `HttpServer` and hand-rolling JSON parsing gave me a much clearer picture of what frameworks normally abstract away, from routing requests to serializing responses.

I also learned how far you can get on the frontend without a bundler or framework, and where that approach starts to show its limits. Building tracking without user accounts pushed me to think through trade-offs around using `localStorage` as a lightweight identity, and setting up two separate deployments (Render for the backend, Vercel for the frontend) taught me more about coordinating environments and config across services than a single-host deployment would have.

## 🏃 Running the Project

To run the project in your local environment, follow these steps:

1. Clone the repository to your local machine.
2. Build the frontend:
   ```
   cd frontend && npm install && npm run build
   ```
3. Build and run the backend:
   ```
   cd ../backend
   javac -cp libs/sqlite-jdbc-3.53.4.0.jar -d out $(find src -name "*.java")
   java -cp "out:libs/sqlite-jdbc-3.53.4.0.jar" com.laurier.events.Main
   ```
4. Open `http://localhost:8080` in your browser to view the app. The database seeds itself with sample Laurier events on first run.

## 🎥 Video

https://github.com/user-attachments/assets/0ecb6068-598e-4e40-a5e8-b4bd082ecbf7

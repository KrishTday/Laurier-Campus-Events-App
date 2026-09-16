# Laurier Campus Events App

A full-stack events tracker for Wilfrid Laurier students, built with Java, TypeScript, CSS, HTML, and SQLite. Surfaces campus events and RSVP deadlines in one place instead of scattered club emails.
<img width="1050" height="1079" alt="Image" src="https://github.com/user-attachments/assets/e61d579b-4187-4141-9c2c-f38a71ae29b3" />

## Overview

Students browse events by category or search, see how far off an RSVP deadline is at a glance, and track the ones they care about. Tracking persists per browser without requiring an account. Organizer mode lets anyone add or remove events, standing in for a real event-management workflow.

## Tech stack

Backend is plain Java (JDK's built-in `HttpServer`, no framework) with SQLite via JDBC, plus a small hand-rolled JSON reader/writer so the whole backend is just the JDK and one driver jar. Frontend is TypeScript compiled straight to browser JavaScript with no bundler, styled with plain CSS. There's no ORM, no build tool beyond `tsc`, and no client-side framework — the DOM is updated directly from `main.ts`.

## Features

Category filters and free-text search across title, description, and location. RSVP deadline countdowns that highlight in red once a deadline is within 48 hours. Per-browser event tracking with a "my tracked events" view. Organizer mode for creating and deleting events through the same REST API the UI uses.

## Setup

```bash
cd frontend && npm install && npm run build
cd ../backend
javac -cp libs/sqlite-jdbc-3.53.4.0.jar -d out $(find src -name "*.java")
java -cp "out:libs/sqlite-jdbc-3.53.4.0.jar" com.laurier.events.Main
```

Open `http://localhost:8080`. The database seeds itself with sample Laurier events on first run.

## Deployment

Backend deploys to Render from the included `Dockerfile` / `render.yaml`. Frontend deploys to Vercel using `vercel.json`, pointed at the Render backend's URL via `frontend/src/config.ts`.

## Known limitations

Students aren't authenticated — tracking is keyed to a random id stored in `localStorage`, so it resets if a student clears their browser data or switches devices. Organizer mode has no access control; anyone can add or delete events. On Render's free tier the SQLite file isn't on a persistent disk, so it reseeds with sample data on every restart.

/**
 * Base URL the frontend calls for the REST API.
 *
 * Leave this empty for local dev, where one Java process serves both the
 * API and the static frontend from the same origin (see backend/web).
 *
 * For a split deployment (frontend on Vercel, backend on Render/Railway/
 * etc.), set this to the deployed backend's origin, e.g.:
 *   export const API_BASE = "https://laurier-events-api.onrender.com";
 * then rebuild (`npm run build`) before deploying the frontend.
 */
export const API_BASE = "";

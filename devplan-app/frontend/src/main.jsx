import React from 'react';
import { createRoot } from 'react-dom/client';
import Keycloak from 'keycloak-js';
import { setKeycloak } from './api.js';
import App from './App.jsx';
import './styles.css';

// The Keycloak redirect replaces the URL fragment with its own login response, which would lose a deep link such as
// #/tasks/<id>; keep the app route in sessionStorage across the redirect and put it back afterwards.
const ROUTE_KEY = 'devplan.route';
const storage = (action) => { try { return action(window.sessionStorage); } catch { return null; } };
if (window.location.hash.startsWith('#/')) storage((s) => s.setItem(ROUTE_KEY, window.location.hash));

// Log in with Keycloak first (settings come from the backend), then render the app.
const config = await fetch('/api/config').then((r) => r.json());
const keycloak = new Keycloak({ url: config.keycloakUrl, realm: config.realm, clientId: config.clientId });
await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false });
setKeycloak(keycloak);

const savedRoute = storage((s) => s.getItem(ROUTE_KEY));
storage((s) => s.removeItem(ROUTE_KEY));
if (savedRoute && !window.location.hash.startsWith('#/')) window.history.replaceState(null, '', savedRoute);

createRoot(document.getElementById('root')).render(<App keycloak={keycloak} />);

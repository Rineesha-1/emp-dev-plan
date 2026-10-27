import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The production build is served by nginx (Dockerfile, nginx.conf); `npm run dev` proxies the API to the app on :8090.
export default defineConfig({
  plugins: [react()],
  build: { target: 'es2022' }, // top-level await in main.jsx
  server: { port: 5173, proxy: { '/api': 'http://localhost:8090' } },
});

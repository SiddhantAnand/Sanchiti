import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: proxies the API to a local backend, or to API_URL (e.g. the live API). Production: nginx (see Dockerfile)
// serves dist/ and proxies /api.
export default defineConfig(({ mode }) => {
  const api = loadEnv(mode, '.', '').API_URL || 'http://127.0.0.1:8787';
  const target = { target: api, changeOrigin: true };
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: { '/api': target, '/health': target },
    },
    build: {
      outDir: 'dist',
      emptyOutDir: true,
      // Recharts dominates the bundle; one chunk is fine for a small app.
      chunkSizeWarningLimit: 800,
    },
  };
});

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const api = 'http://127.0.0.1:8787';

// Dev: proxies the API to a local backend. Production: nginx (see Dockerfile) serves dist/ and proxies /api.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': api, '/health': api },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // Recharts dominates the bundle; one chunk is fine for a small app.
    chunkSizeWarningLimit: 800,
  },
});

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// /api/* → Spring Boot on :8080 with the /api prefix stripped, so the browser never hits CORS.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});

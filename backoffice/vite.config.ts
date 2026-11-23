import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [react(), {
    name: 'demo-entry-gate',
    configureServer(server) {
      if (mode === 'demo') return;
      server.middlewares.use((request, response, next) => {
        const path = (request as typeof request & { url?: string }).url?.split('?')[0];
        if (path === '/demo.html') {
          response.statusCode = 404;
          response.end();
          return;
        }
        next();
      });
    },
  }],
  build: { rollupOptions: { input: mode === 'demo' ? 'demo.html' : 'index.html' } },
  server: { proxy: { '/api': { target: 'http://localhost:8082', changeOrigin: false } } },
}));

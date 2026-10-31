import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// 개발 서버가 /api, /test-api 를 Delivery(local-test) 서버로 프록시한다. 운영 서버에는 /test-api 가 존재하지 않는다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.VITE_BACKEND ?? 'http://127.0.0.1:8080';
  return {
    plugins: [react()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': backend,
        '/test-api': backend,
      },
    },
  };
});

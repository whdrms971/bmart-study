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
      // 다른 프로젝트가 5173 을 쓰는 PC 에서는 VITE_PORT 로 바꾼다. 다른 포트로 조용히 넘어가지 않도록 strictPort.
      port: Number(env.VITE_PORT ?? 5173),
      strictPort: true,
      proxy: {
        '/api': backend,
        '/test-api': backend,
      },
    },
  };
});

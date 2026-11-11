import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// 개발 중에는 /api 를 백엔드(기본 5534)로 넘긴다. 운영은 같은 서버가 정적 파일을 서빙하므로 프록시가 필요 없다.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:5534',
    },
  },
})

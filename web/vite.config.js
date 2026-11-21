import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// `npm run dev` proxies the API to `uv run jarvis.py --serve`; `npm run build` writes dist/, which that server serves.
const api = 'http://127.0.0.1:8765'
export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: Object.fromEntries(['/ask', '/tool_result', '/transcribe', '/speak', '/health', '/models'].map((p) => [p, api])),
  },
})

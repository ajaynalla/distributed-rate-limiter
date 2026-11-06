import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  base: process.env.VITE_BASE_PATH || "/",
  server: {
    proxy: {
      "/v1": "http://127.0.0.1:8080",
      "/actuator": "http://127.0.0.1:8080",
    },
  },
  test: { environment: "jsdom", restoreMocks: true },
});

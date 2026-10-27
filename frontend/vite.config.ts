import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "/react-workbench/",
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/static/react-workbench",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/actuator": "http://localhost:8080",
    },
  },
});

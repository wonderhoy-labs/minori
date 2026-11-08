import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
	// Relative, so that a copy of the site works from any address, GitHub Pages included.
	base: "./",
	plugins: [react()],
	build: { target: "esnext" },
	test: { environment: "node", include: ["src/**/*.test.ts"] },
});

import { defineConfig } from "vitest/config";

// The library's own build: one ES module, with its dependencies left outside.
export default defineConfig({
	build: {
		target: "es2022",
		minify: false,
		lib: { entry: "src/index.ts", formats: ["es"], fileName: () => "index.js" },
		rollupOptions: { external: (id) => !id.startsWith(".") && !id.startsWith("/") && !id.includes("?raw") },
	},
	test: { environment: "node", include: ["src/**/*.test.ts"] },
});

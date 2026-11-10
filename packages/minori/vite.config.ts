import { defineConfig } from "vitest/config";

// The library's own build. It has no dependencies, so the package works with any bundler.
export default defineConfig({
	build: {
		target: "es2022",
		minify: false,
		lib: { entry: "src/index.ts", formats: ["es"], fileName: () => "index.js" },
	},
	test: { environment: "node", include: ["src/**/*.test.ts"] },
});

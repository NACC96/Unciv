import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // ExFAT checkouts sprout macOS AppleDouble `._` sidecars; they match
    // *.test.ts globs and crash esbuild. Never treat them as test files.
    exclude: ["**/._*", "**/node_modules/**", "**/dist/**"],
  },
});

import { describe, expect, it } from "vitest";
import { greet } from "./index";

describe("greet", () => {
	it("greets by name", () => {
		expect(greet("Miku")).toBe("Hello, Miku!");
	});
});

import { expect, test } from "vitest";
import { brand } from "./brand";

test("the brand has a name and an image colour", () => {
	expect(brand.name).not.toBe("");
	expect(brand.color).toMatch(/^#[0-9A-F]{6}$/);
});

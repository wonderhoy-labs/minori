import { describe, expect, it } from "vitest";
import { desEncrypt, vncResponse } from "./des";

const hex = (text: string) => Uint8Array.from(text.match(/../g) ?? [], (byte) => Number.parseInt(byte, 16));
const toHex = (bytes: Uint8Array) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");

describe("DES", () => {
	it("matches the classic worked example", () => {
		expect(toHex(desEncrypt(hex("133457799BBCDFF1"), hex("0123456789ABCDEF")))).toBe("85e813540f0ab405");
	});

	it("matches a NIST known-answer vector", () => {
		expect(toHex(desEncrypt(hex("0101010101010101"), hex("8000000000000000")))).toBe("95f8a5e5dd31d900");
	});
});

describe("VNC's password check", () => {
	it("uses the password with each key byte's bits reversed", () => {
		const challenge = hex("00112233445566778899aabbccddeeff");
		// "test-pas" reversed bit by bit is 2e a6 ce 2e b4 0e 86 ce.
		const key = hex("2ea6ce2eb40e86ce");
		const expected = new Uint8Array(16);
		expected.set(desEncrypt(key, challenge.subarray(0, 8)), 0);
		expected.set(desEncrypt(key, challenge.subarray(8)), 8);
		expect(toHex(vncResponse("test-pas", challenge))).toBe(toHex(expected));
	});

	it("only uses the first eight characters", () => {
		const challenge = hex("ffeeddccbbaa99887766554433221100");
		expect(vncResponse("test-pass-1234", challenge)).toEqual(vncResponse("test-pas", challenge));
	});
});

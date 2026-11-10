import { describe, expect, it } from "vitest";
import { vncResponse } from "./des";
import { ByteReader } from "./stream";
import { pipe } from "./testing";
import { type FrameTarget, keysym, pictureBox, VncClient } from "./vnc";

/** Keeps a session's pixels in memory. */
class MemoryTarget implements FrameTarget {
	width = 0;
	height = 0;
	pixels = new Uint8ClampedArray(0);
	resize(width: number, height: number) {
		this.width = width;
		this.height = height;
		this.pixels = new Uint8ClampedArray(width * height * 4);
	}
	put(x: number, y: number, w: number, h: number, rgba: Uint8ClampedArray) {
		for (let row = 0; row < h; row++)
			this.pixels.set(rgba.subarray(row * w * 4, (row + 1) * w * 4), ((y + row) * this.width + x) * 4);
	}
	copy(fromX: number, fromY: number, x: number, y: number, w: number, h: number) {
		const copy = new Uint8ClampedArray(w * h * 4);
		for (let row = 0; row < h; row++)
			copy.set(
				this.pixels.subarray(
					((fromY + row) * this.width + fromX) * 4,
					((fromY + row) * this.width + fromX + w) * 4,
				),
				row * w * 4,
			);
		this.put(x, y, w, h, copy);
	}
	at(x: number, y: number) {
		return Array.from(this.pixels.subarray((y * this.width + x) * 4, (y * this.width + x) * 4 + 4));
	}
}

const u16 = (n: number) => [n >> 8, n & 255];
const u32 = (n: number) => [(n >>> 24) & 255, (n >> 16) & 255, (n >> 8) & 255, n & 255];

describe("the VNC client", () => {
	it("logs in with a VNC password and draws raw and copied rectangles", async () => {
		const p = pipe();
		const server = new ByteReader(p.fromClient);
		const send = (bytes: number[] | Uint8Array) => p.toClient.write(Uint8Array.from(bytes));
		const challenge = Uint8Array.from({ length: 16 }, (_, i) => i * 7);
		const serverSide = (async () => {
			await send(new TextEncoder().encode("RFB 003.008\n"));
			expect(new TextDecoder().decode(await server.read(12))).toBe("RFB 003.008\n");
			await send([1, 2]);
			expect(await server.u8()).toBe(2);
			await send(challenge);
			expect(await server.read(16)).toEqual(vncResponse("secret12", challenge));
			await send(u32(0));
			expect(await server.u8()).toBe(1);
			const name = new TextEncoder().encode("Debian");
			await send([...u16(4), ...u16(2), ...new Array(16).fill(0), ...u32(name.length), ...name]);
			const format = await server.read(20);
			expect(Array.from(format.subarray(4, 8))).toEqual([32, 24, 0, 1]);
			expect(await server.u8()).toBe(2);
			await server.read(1);
			await server.read((await server.u16()) * 4);
			expect(Array.from(await server.read(10))).toEqual([3, 0, 0, 0, 0, 0, ...u16(4), ...u16(2)]);
			// One update: a red pixel at (0, 0) and a green one at (1, 0), then (0, 0)-(1, 0) copied to the second row.
			await send([0, 0, ...u16(2)]);
			await send([...u16(0), ...u16(0), ...u16(2), ...u16(1), ...u32(0), 255, 0, 0, 0, 0, 255, 0, 0]);
			await send([...u16(0), ...u16(1), ...u16(2), ...u16(1), ...u32(1), ...u16(0), ...u16(0)]);
			expect((await server.read(10))[1]).toBe(1);
		})();
		const target = new MemoryTarget();
		let frames = 0;
		// A failed expectation on the server's side would otherwise leave the client waiting.
		const failed = serverSide.then(
			() => new Promise<never>(() => undefined),
			(error) => Promise.reject(error),
		);
		const client = await Promise.race([
			VncClient.connect(p.client, target, "secret12", { onFrame: () => frames++ }),
			failed,
		]);
		await serverSide;
		expect([client.width, client.height, client.name]).toEqual([4, 2, "Debian"]);
		expect(frames).toBe(1);
		expect(target.at(0, 0)).toEqual([255, 0, 0, 255]);
		expect(target.at(1, 0)).toEqual([0, 255, 0, 255]);
		expect(target.at(0, 1)).toEqual([255, 0, 0, 255]);
		expect(target.at(1, 1)).toEqual([0, 255, 0, 255]);
		await client.close();
	});

	it("says why a server refused", async () => {
		const p = pipe();
		const send = (bytes: number[] | Uint8Array) => p.toClient.write(Uint8Array.from(bytes));
		const reason = new TextEncoder().encode("Too many viewers");
		void (async () => {
			await send(new TextEncoder().encode("RFB 003.008\n"));
			await send([0, ...u32(reason.length), ...reason]);
		})();
		await expect(VncClient.connect(p.client, new MemoryTarget())).rejects.toThrow("Too many viewers");
	});
});

describe("keysym", () => {
	it("maps browser keys to X11 keysyms", () => {
		expect(keysym({ key: "a", code: "KeyA" })).toBe(0x61);
		expect(keysym({ key: "é", code: "KeyE" })).toBe(0xe9);
		expect(keysym({ key: "€", code: "KeyE" })).toBe(0x010020ac);
		expect(keysym({ key: "Enter", code: "Enter" })).toBe(0xff0d);
		expect(keysym({ key: "F5", code: "F5" })).toBe(0xffc2);
		expect(keysym({ key: "Shift", code: "ShiftLeft" })).toBe(0xffe1);
		expect(keysym({ key: "Dead", code: "Quote" })).toBeNull();
	});
});

describe("pictureBox", () => {
	it("finds a letterboxed desktop inside its canvas", () => {
		// A 16:9 desktop in a taller box is shown full width, centred vertically.
		expect(pictureBox({ left: 0, top: 38, width: 800, height: 562 }, 1280, 720, "contain")).toEqual({
			left: 0,
			top: 38 + (562 - 450) / 2,
			width: 800,
			height: 450,
		});
	});

	it("uses the whole box when the picture fills it", () => {
		const box = { left: 10, top: 20, width: 300, height: 100 };
		expect(pictureBox(box, 1280, 720, "fill")).toBe(box);
	});
});

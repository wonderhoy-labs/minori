import { describe, expect, it } from "vitest";
import { HELPER_SERVICE, Minori, MinoriError } from "./helper";
import { ByteReader } from "./stream";
import { type Pipe, pipe } from "./testing";

/** A fake helper that answers each connection's first request with [answer], then any bytes in [after]. */
function fakeHelper(
	answer: (request: { id: number; method: string; params: Record<string, unknown> }) => object,
	after?: Uint8Array,
) {
	const requests: { method: string; params: Record<string, unknown> }[] = [];
	const pipes: Pipe[] = [];
	const minori = new Minori(() => {
		const p = pipe();
		pipes.push(p);
		void (async () => {
			const reader = new ByteReader(p.fromClient);
			const line = await reader.line();
			if (line === null) return;
			const request = JSON.parse(line);
			requests.push(request);
			const reply = new TextEncoder().encode(`${JSON.stringify({ id: request.id, ...answer(request) })}\n`);
			if (after) {
				const joined = new Uint8Array(reply.length + after.length);
				joined.set(reply);
				joined.set(after, reply.length);
				await p.toClient.write(joined);
			} else {
				await p.toClient.write(reply);
			}
		})();
		return Promise.resolve(p.client);
	});
	return { minori, requests, pipes };
}

describe("Minori's helper client", () => {
	it("lists machines", async () => {
		const machine = {
			id: "a",
			name: "Debian",
			backend: "avf",
			state: "running",
			agent: true,
			vncPort: 5901,
			sshPort: 22,
			rdpPort: 3389,
			user: "user",
		};
		const { minori, requests, pipes } = fakeHelper(() => ({ result: [machine] }));
		expect(await minori.list()).toEqual([machine]);
		expect(requests).toEqual([expect.objectContaining({ method: "list", params: {} })]);
		expect(pipes[0]?.closed()).toBe(true);
	});

	it("passes the machine and port, and Minori's own errors", async () => {
		const { minori, requests } = fakeHelper(() => ({ error: "There's no machine Arch" }));
		await expect(minori.start("Arch")).rejects.toEqual(new MinoriError("There's no machine Arch"));
		expect(requests[0]).toEqual(expect.objectContaining({ method: "start", params: { vm: "Arch" } }));
	});

	it("hands over a stream with the bytes that came with the reply", async () => {
		const banner = new TextEncoder().encode("SSH-2.0-OpenSSH_10.0\r\n");
		const { minori, requests } = fakeHelper(() => ({ result: { stream: true } }), banner);
		const stream = await minori.connect("Debian", 22);
		expect(requests[0]).toEqual(expect.objectContaining({ method: "connect", params: { vm: "Debian", port: 22 } }));
		const reader = new ByteReader(stream.readable);
		expect(await reader.line()).toBe("SSH-2.0-OpenSSH_10.0\r");
	});

	it("names the adb service it uses", () => {
		expect(HELPER_SERVICE).toBe("localabstract:minori");
	});
});

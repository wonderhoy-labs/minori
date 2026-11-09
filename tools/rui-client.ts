// A stand-in for Asahi: talks to Rui's on-device helper the way a page would, to check the contract in
// docs/asahi-integration.md. Forward the helper first:
//
//   adb forward tcp:5742 localabstract:rui
//   node tools/rui-client.ts check
//   node tools/rui-client.ts call list
//   node tools/rui-client.ts call ports '{"vm":"Debian"}'
//
// `check` says hello, lists the machines, asks each running one for its ports, and opens a stream to the SSH
// port of the first running machine to read the server's banner. It exits with 1 if anything fails.

import { connect, type Socket } from "node:net";

type Reply = { id?: number; result?: unknown; error?: string };
type Machine = { id: string; name: string; state: string; sshPort: number };

const port = Number(process.env.RUI_HELPER_PORT ?? "5742");

function open(): Promise<Socket> {
	return new Promise((resolve, reject) => {
		const socket = connect({ host: "127.0.0.1", port }, () => resolve(socket));
		socket.once("error", reject);
	});
}

/** Reads one line, leaving whatever follows it unread. */
function readLine(socket: Socket): Promise<string> {
	return new Promise((resolve, reject) => {
		let text = "";
		const onData = (chunk: Buffer) => {
			const at = chunk.indexOf(10);
			if (at < 0) {
				text += chunk.toString("utf8");
				return;
			}
			text += chunk.subarray(0, at).toString("utf8");
			socket.off("data", onData);
			socket.off("error", reject);
			const rest = chunk.subarray(at + 1);
			if (rest.length > 0) socket.unshift(rest);
			resolve(text);
		};
		socket.on("data", onData);
		socket.once("error", reject);
	});
}

let nextId = 1;

async function request(socket: Socket, method: string, params: object = {}): Promise<Reply> {
	const id = nextId++;
	socket.write(`${JSON.stringify({ id, method, params })}\n`);
	const reply = JSON.parse(await readLine(socket)) as Reply;
	if (reply.id !== id) throw new Error(`expected a reply to ${id}, got ${JSON.stringify(reply)}`);
	return reply;
}

async function call(method: string, params: object): Promise<void> {
	const socket = await open();
	console.info(JSON.stringify(await request(socket, method, params), null, "\t"));
	socket.end();
}

async function check(): Promise<void> {
	const socket = await open();
	const hello = await request(socket, "hello");
	console.info("hello:", JSON.stringify(hello.result));
	const list = await request(socket, "list");
	if (!Array.isArray(list.result)) throw new Error(`list failed: ${list.error}`);
	const machines = list.result as Machine[];
	console.info(`list: ${machines.map((vm) => `${vm.name} (${vm.state})`).join(", ") || "no machines"}`);
	const running = machines.filter((vm) => vm.state === "running");
	for (const vm of running) {
		const ports = await request(socket, "ports", { vm: vm.id });
		console.info(`ports of ${vm.name}:`, JSON.stringify(ports.result ?? ports.error));
	}
	const unknown = await request(socket, "ports", { vm: "no-such-machine" });
	if (!unknown.error) throw new Error("asking about a missing machine should fail");
	console.info("missing machine:", unknown.error);
	socket.end();
	const first = running[0];
	if (!first) {
		console.info("No machine is running, so the stream test was skipped.");
		return;
	}
	const stream = await open();
	const reply = await request(stream, "connect", { vm: first.id, port: first.sshPort });
	if (reply.error) throw new Error(`connect failed: ${reply.error}`);
	const banner = await readLine(stream);
	console.info(`stream to ${first.name}:${first.sshPort}: ${banner.trim()}`);
	if (!banner.startsWith("SSH-")) throw new Error("expected an SSH banner");
	stream.end();
}

const [command, ...rest] = process.argv.slice(2);
try {
	if (command === "check") {
		await check();
	} else if (command === "call" && rest[0]) {
		await call(rest[0], rest[1] ? (JSON.parse(rest[1]) as object) : {});
	} else {
		console.error("Usage: node tools/rui-client.ts check | call <method> [params as JSON]");
		process.exitCode = 2;
	}
} catch (error) {
	console.error((error as Error).message);
	process.exitCode = 1;
}

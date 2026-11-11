import { ByteReader, type Duplex } from "./stream";

/** The adb service of Minori's helper: an abstract Unix socket that only adb's shell user and root may use. */
export const HELPER_SERVICE = "localabstract:minori";

/** The helper protocol this library speaks. */
export const PROTOCOL = 1;

/** Minori's Android package. */
export const PACKAGE = "com.wonderhoylabs.minori";

/**
 * The phone command that starts Minori, and with it the helper, without opening its screen. Minori then keeps running,
 * with a notification, until a minute after the last request, since Android freezes apps in the background. Only adb's
 * shell may run it. Run it through adb, then retry the helper for a few seconds.
 */
export const WAKE_COMMAND = `am start-foreground-service -a ${PACKAGE}.WAKE -n ${PACKAGE}/.service.MinoriService`;

/** What runs a machine: a real VM, Termux, or the demo shell of debug builds. */
export type Backend = "avf" | "termux" | "demo";

/** Where a machine is in its life. */
export type MachineState = "stopped" | "starting" | "running" | "stopping" | "error";

/** One of Minori's machines, as the helper describes it. */
export interface Machine {
	id: string;
	name: string;
	backend: Backend;
	state: MachineState;
	/** The guest's operating system, when Minori knows it. */
	os?: string;
	/** Whether Minori's guest agent is in it, which ports, desktops and its internet need. */
	agent: boolean;
	/** The guest's VNC port, for its desktop. */
	vncPort: number;
	sshPort: number;
	rdpPort: number;
	/** The user that logs in. */
	user: string;
	/**
	 * Whether Airi is set up in it. Then Airi lists its apps while it runs, and
	 * `minori://vm/<id>/app/<desktop file ID>` opens one. Missing before 0.2.0.
	 */
	airi?: boolean;
}

/** A port the user published from a machine to the phone. */
export interface PublishedPort {
	hostPort: number;
	guestPort: number;
	protocol: "tcp" | "udp";
	exposure: "loopback" | "network";
	listening: boolean;
}

/** A machine's ports: what the user published, and what listens in the guest, if its agent says. */
export interface Ports {
	published: PublishedPort[];
	listening?: number[];
}

/** A machine's graphical desktop: its VNC server's port in the guest, and the password if it needs one. */
export interface Desktop {
	port: number;
	password?: string;
}

/** Who answered, and what it can do. */
export interface Hello {
	name: string;
	version: string;
	protocol: number;
	methods: string[];
}

/** Something Minori refused or couldn't do. The message is Minori's, written for people. */
export class MinoriError extends Error {
	override name = "MinoriError";
}

/** Anything that opens adb services, such as Tango's `Adb`. */
export interface SocketOpener {
	createSocket(service: string): Promise<Duplex>;
}

/**
 * Talks to Minori's helper on the phone. Every call opens its own connection, so calls may overlap, and streams
 * ([connect], [console]) are connections of their own that the caller closes.
 */
export class Minori {
	private nextId = 1;

	/** [open] makes a fresh connection to the helper each time it's called. */
	constructor(private readonly open: () => Promise<Duplex>) {}

	/** A client that reaches the helper through an adb connection. */
	static overAdb(adb: SocketOpener): Minori {
		return new Minori(() => adb.createSocket(HELPER_SERVICE));
	}

	private async request(method: string, params: Record<string, unknown> = {}) {
		const connection = await this.open();
		const reader = new ByteReader(connection.readable);
		const writer = connection.writable.getWriter();
		const id = this.nextId++;
		try {
			await writer.write(new TextEncoder().encode(`${JSON.stringify({ id, method, params })}\n`));
			const line = await reader.line();
			if (line === null) throw new MinoriError("Minori closed the connection without answering");
			const reply = JSON.parse(line) as { id?: number; result?: unknown; error?: string };
			if (reply.id !== id) throw new MinoriError("Minori answered a different request");
			if (reply.error !== undefined) throw new MinoriError(reply.error);
			return { connection, reader, writer, result: reply.result };
		} catch (error) {
			writer.releaseLock();
			await connection.close();
			throw error;
		}
	}

	private async call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
		const { connection, writer, result } = await this.request(method, params);
		writer.releaseLock();
		await connection.close();
		return result as T;
	}

	private async stream(method: string, params: Record<string, unknown>): Promise<Duplex> {
		const { connection, reader, writer } = await this.request(method, params);
		writer.releaseLock();
		return { readable: reader.rest(), writable: connection.writable, close: () => connection.close() };
	}

	/** Minori's name and version, and the methods it answers. */
	hello(): Promise<Hello> {
		return this.call("hello");
	}

	/** Every machine. */
	list(): Promise<Machine[]> {
		return this.call("list");
	}

	/** Starts a machine, by id or name. It answers at once; the machine keeps starting after. */
	start(vm: string): Promise<Machine> {
		return this.call("start", { vm });
	}

	/** Asks a machine to shut down, then stops it after 30 seconds if it hasn't. */
	stop(vm: string): Promise<Machine> {
		return this.call("stop", { vm });
	}

	ports(vm: string): Promise<Ports> {
		return this.call("ports", { vm });
	}

	/** Where a machine's desktop is, and its VNC password if it has one. Open it with [connect] and [VncClient]. */
	desktop(vm: string): Promise<Desktop> {
		return this.call("desktop", { vm });
	}

	/** A byte stream to [port] in a running machine's guest, such as its VNC or SSH server. */
	connect(vm: string, port: number): Promise<Duplex> {
		return this.stream("connect", { vm, port });
	}

	/** A running machine's console: what it prints, and the keys you send it. */
	console(vm: string): Promise<Duplex> {
		return this.stream("console", { vm });
	}
}

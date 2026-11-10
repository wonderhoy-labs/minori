// Test helpers: a pair of connected streams, like a socket and the server at its other end.
import type { Duplex } from "./stream";

export interface Pipe {
	/** The client's end. */
	client: Duplex;
	/** What the client wrote. */
	fromClient: ReadableStream<Uint8Array>;
	/** Writes to the client. */
	toClient: WritableStreamDefaultWriter<Uint8Array>;
	closed: () => boolean;
}

export function pipe(): Pipe {
	// Buffered like a socket, so a write doesn't wait for the other end to read it.
	const buffered = (): TransformStream<Uint8Array, Uint8Array> =>
		new TransformStream<Uint8Array, Uint8Array>({}, { highWaterMark: 1024 }, { highWaterMark: 1024 });
	const down = buffered();
	const up = buffered();
	let closed = false;
	return {
		client: {
			readable: down.readable,
			writable: up.writable,
			close: () => {
				closed = true;
			},
		},
		fromClient: up.readable,
		toClient: down.writable.getWriter(),
		closed: () => closed,
	};
}

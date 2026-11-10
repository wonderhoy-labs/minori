/** Where bytes come from: a `ReadableStream`, or anything with the same reader, such as Tango's. */
export interface ByteSource {
	getReader(): ByteSourceReader;
}

export interface ByteSourceReader {
	read(): Promise<{ done: boolean; value?: Uint8Array }>;
	cancel(reason?: unknown): Promise<void>;
}

/** Where bytes go: a `WritableStream`, or anything with the same writer, such as Tango's. */
export interface ByteSink {
	getWriter(): ByteSinkWriter;
}

export interface ByteSinkWriter {
	write(chunk: Uint8Array): Promise<void>;
	releaseLock(): void;
}

/** A two-way byte stream: an adb socket, or anything else with the same shape. */
export interface Duplex {
	readonly readable: ByteSource;
	readonly writable: ByteSink;
	close(): PromiseLike<void> | void;
}

/** Reads exact lengths and lines from a stream, keeping whatever comes after for the next read. */
export class ByteReader {
	private readonly reader: ByteSourceReader;
	private buffered: Uint8Array = new Uint8Array(0);

	constructor(readable: ByteSource) {
		this.reader = readable.getReader();
	}

	private async fill(): Promise<boolean> {
		const { value, done } = await this.reader.read();
		if (done || !value) return false;
		if (this.buffered.length === 0) {
			this.buffered = value;
		} else {
			const joined = new Uint8Array(this.buffered.length + value.length);
			joined.set(this.buffered);
			joined.set(value, this.buffered.length);
			this.buffered = joined;
		}
		return true;
	}

	/** Exactly [length] bytes. Throws if the stream ends first. */
	async read(length: number): Promise<Uint8Array> {
		while (this.buffered.length < length) {
			if (!(await this.fill())) throw new Error("The connection closed");
		}
		const out = this.buffered.slice(0, length);
		this.buffered = this.buffered.subarray(length);
		return out;
	}

	async u8(): Promise<number> {
		return (await this.read(1))[0] as number;
	}

	async u16(): Promise<number> {
		const b = await this.read(2);
		return ((b[0] as number) << 8) | (b[1] as number);
	}

	async u32(): Promise<number> {
		const b = await this.read(4);
		return new DataView(b.buffer, b.byteOffset, 4).getUint32(0);
	}

	async s32(): Promise<number> {
		const b = await this.read(4);
		return new DataView(b.buffer, b.byteOffset, 4).getInt32(0);
	}

	/** One line without its newline, or null if the stream ends first. Lines are capped at [max] bytes. */
	async line(max = 1 << 20): Promise<string | null> {
		for (;;) {
			const at = this.buffered.indexOf(10);
			if (at >= 0) {
				const text = new TextDecoder().decode(this.buffered.subarray(0, at));
				this.buffered = this.buffered.subarray(at + 1);
				return text;
			}
			if (this.buffered.length > max) throw new Error("The line was too long");
			if (!(await this.fill())) return null;
		}
	}

	/** The rest of the stream, starting with anything already read but not yet used. */
	rest(): ReadableStream<Uint8Array> {
		let pending: Uint8Array | null = this.buffered.length > 0 ? this.buffered : null;
		this.buffered = new Uint8Array(0);
		const reader = this.reader;
		return new ReadableStream<Uint8Array>({
			async pull(controller) {
				if (pending) {
					controller.enqueue(pending);
					pending = null;
					return;
				}
				const { value, done } = await reader.read();
				if (done || !value) controller.close();
				else controller.enqueue(value);
			},
			cancel(reason) {
				return reader.cancel(reason);
			},
		});
	}

	cancel(): Promise<void> {
		return this.reader.cancel();
	}
}

import { vncResponse } from "./des";
import { ByteReader, type ByteSinkWriter, type Duplex } from "./stream";

const RAW = 0;
const COPY_RECT = 1;
const DESKTOP_SIZE = -223;
const SECURITY_NONE = 1;
const SECURITY_VNC = 2;

/** Where a VNC session's pixels go. [canvasTarget] draws them on a canvas; tests keep them in memory. */
export interface FrameTarget {
	/** The desktop's size, at the start and whenever it changes. */
	resize(width: number, height: number): void;
	/** RGBA pixels for a rectangle, [width] × [height] × 4 bytes. */
	put(x: number, y: number, width: number, height: number, rgba: Uint8ClampedArray): void;
	/** Copies a rectangle within the desktop. */
	copy(fromX: number, fromY: number, x: number, y: number, width: number, height: number): void;
}

/** What a session tells its caller as it goes. */
export interface VncEvents {
	/** A whole update has been drawn. */
	onFrame?(): void;
	/** The connection ended, with the reason if it failed. */
	onClose?(error?: Error): void;
}

/**
 * A small VNC (RFB 3.8) client: no password or a VNC password, raw and copy-rect updates in 32-bit colour, the pointer
 * and the keyboard. It's meant for a guest desktop through Minori, where the link is a local USB cable and raw pixels
 * cost nothing to decode. Start it with [VncClient.connect].
 */
export class VncClient {
	/** The desktop's width in pixels. */
	width = 0;
	/** The desktop's height in pixels. */
	height = 0;
	/** The desktop's name, as the server gives it. */
	name = "";
	private readonly writer: ByteSinkWriter;
	private closed = false;

	private constructor(
		private readonly stream: Duplex,
		private readonly reader: ByteReader,
		private readonly target: FrameTarget,
		private readonly events: VncEvents,
	) {
		this.writer = stream.writable.getWriter();
	}

	/**
	 * Does the handshake over [stream], usually [Minori.connect] to the machine's `vncPort`, then keeps drawing into
	 * [target] until [close]. Throws, with a message for people, if the server wants something this client can't do.
	 */
	static async connect(
		stream: Duplex,
		target: FrameTarget,
		password?: string,
		events: VncEvents = {},
	): Promise<VncClient> {
		const client = new VncClient(stream, new ByteReader(stream.readable), target, events);
		try {
			await client.handshake(password);
		} catch (error) {
			await client.close();
			throw error;
		}
		void client.run();
		return client;
	}

	private send(bytes: Uint8Array): Promise<void> {
		if (this.closed) return Promise.resolve();
		return this.writer.write(bytes).catch(() => undefined);
	}

	private async reason(): Promise<string> {
		const length = Math.min(await this.reader.u32(), 4096);
		return new TextDecoder().decode(await this.reader.read(length));
	}

	private async handshake(password?: string) {
		const version = new TextDecoder().decode(await this.reader.read(12));
		if (!version.startsWith("RFB ")) throw new Error("That port isn't a VNC server");
		await this.send(new TextEncoder().encode("RFB 003.008\n"));
		const count = await this.reader.u8();
		if (count === 0) throw new Error(await this.reason());
		const types = Array.from(await this.reader.read(count));
		if (types.includes(SECURITY_NONE)) {
			await this.send(Uint8Array.of(SECURITY_NONE));
		} else if (types.includes(SECURITY_VNC)) {
			if (password === undefined) throw new Error("This desktop needs its VNC password");
			await this.send(Uint8Array.of(SECURITY_VNC));
			await this.send(vncResponse(password, await this.reader.read(16)));
		} else {
			throw new Error(
				"This VNC server only offers security this client doesn't support. Allow plain VNC passwords.",
			);
		}
		if ((await this.reader.u32()) !== 0) {
			throw new Error(await this.reason().catch(() => "The VNC password was refused"));
		}
		await this.send(Uint8Array.of(1)); // Share the desktop with other viewers.
		this.width = await this.reader.u16();
		this.height = await this.reader.u16();
		await this.reader.read(16);
		this.name = new TextDecoder().decode(await this.reader.read(await this.reader.u32()));
		this.target.resize(this.width, this.height);
		// 32 bits per pixel, true colour, little-endian, red at bit 0: the bytes arrive as R, G, B, X.
		const format = new Uint8Array(20);
		const view = new DataView(format.buffer);
		format.set([0, 0, 0, 0, 32, 24, 0, 1]);
		view.setUint16(8, 255);
		view.setUint16(10, 255);
		view.setUint16(12, 255);
		format.set([0, 8, 16], 14);
		await this.send(format);
		const encodings = [RAW, COPY_RECT, DESKTOP_SIZE];
		const message = new Uint8Array(4 + encodings.length * 4);
		const encodingView = new DataView(message.buffer);
		message[0] = 2;
		encodingView.setUint16(2, encodings.length);
		for (const [i, encoding] of encodings.entries()) encodingView.setInt32(4 + i * 4, encoding);
		await this.send(message);
		await this.requestUpdate(false);
	}

	private requestUpdate(incremental: boolean): Promise<void> {
		const message = new Uint8Array(10);
		const view = new DataView(message.buffer);
		message[0] = 3;
		message[1] = incremental ? 1 : 0;
		view.setUint16(6, this.width);
		view.setUint16(8, this.height);
		return this.send(message);
	}

	private async run() {
		try {
			for (;;) {
				const type = await this.reader.u8();
				if (type === 0) await this.update();
				else if (type === 1) {
					await this.reader.read(3);
					await this.reader.read((await this.reader.u16()) * 6);
				} else if (type === 2) {
					// Bell.
				} else if (type === 3) {
					await this.reader.read(3);
					await this.reader.read(await this.reader.u32());
				} else {
					throw new Error(`The VNC server sent message ${type}, which this client doesn't understand`);
				}
			}
		} catch (error) {
			const failed = this.closed ? undefined : error instanceof Error ? error : new Error(String(error));
			await this.close();
			this.events.onClose?.(failed);
		}
	}

	private async update() {
		await this.reader.read(1);
		const rects = await this.reader.u16();
		for (let i = 0; i < rects; i++) {
			const x = await this.reader.u16();
			const y = await this.reader.u16();
			const w = await this.reader.u16();
			const h = await this.reader.u16();
			const encoding = await this.reader.s32();
			if (encoding === RAW) {
				const pixels = new Uint8ClampedArray(await this.reader.read(w * h * 4));
				for (let at = 3; at < pixels.length; at += 4) pixels[at] = 255;
				this.target.put(x, y, w, h, pixels);
			} else if (encoding === COPY_RECT) {
				const fromX = await this.reader.u16();
				const fromY = await this.reader.u16();
				this.target.copy(fromX, fromY, x, y, w, h);
			} else if (encoding === DESKTOP_SIZE) {
				this.width = w;
				this.height = h;
				this.target.resize(w, h);
			} else {
				throw new Error(`The VNC server sent encoding ${encoding}, which this client didn't ask for`);
			}
		}
		this.events.onFrame?.();
		await this.requestUpdate(true);
	}

	/** Moves the pointer to a desktop position, with [buttons] held: bit 0 left, 1 middle, 2 right, 3 and 4 the wheel. */
	pointer(x: number, y: number, buttons: number): Promise<void> {
		const message = new Uint8Array(6);
		const view = new DataView(message.buffer);
		message[0] = 5;
		message[1] = buttons;
		view.setUint16(2, Math.max(0, Math.min(this.width - 1, Math.round(x))));
		view.setUint16(4, Math.max(0, Math.min(this.height - 1, Math.round(y))));
		return this.send(message);
	}

	/** Presses or releases a key, by its X11 keysym. [keysym] turns browser keys into keysyms. */
	key(keysym: number, down: boolean): Promise<void> {
		const message = new Uint8Array(8);
		message[0] = 4;
		message[1] = down ? 1 : 0;
		new DataView(message.buffer).setUint32(4, keysym);
		return this.send(message);
	}

	/** Ends the session and closes its stream. */
	async close(): Promise<void> {
		if (this.closed) return;
		this.closed = true;
		this.writer.releaseLock();
		await this.reader.cancel().catch(() => undefined);
		await Promise.resolve(this.stream.close()).catch(() => undefined);
	}
}

const SPECIAL_KEYS: Record<string, number> = {
	Backspace: 0xff08,
	Tab: 0xff09,
	Enter: 0xff0d,
	Escape: 0xff1b,
	Delete: 0xffff,
	Home: 0xff50,
	ArrowLeft: 0xff51,
	ArrowUp: 0xff52,
	ArrowRight: 0xff53,
	ArrowDown: 0xff54,
	PageUp: 0xff55,
	PageDown: 0xff56,
	End: 0xff57,
	Insert: 0xff63,
	ContextMenu: 0xff67,
	CapsLock: 0xffe5,
	NumLock: 0xff7f,
	ScrollLock: 0xff14,
	Pause: 0xff13,
	PrintScreen: 0xff61,
};

const MODIFIERS: Record<string, number> = {
	ShiftLeft: 0xffe1,
	ShiftRight: 0xffe2,
	ControlLeft: 0xffe3,
	ControlRight: 0xffe4,
	MetaLeft: 0xffeb,
	MetaRight: 0xffec,
	AltLeft: 0xffe9,
	AltRight: 0xfe03,
};

/** The X11 keysym for a browser key, from a `KeyboardEvent`'s `key` and `code`, or null for keys VNC can't send. */
export function keysym(event: { key: string; code: string }): number | null {
	const modifier = MODIFIERS[event.code];
	if (modifier !== undefined) return modifier;
	const special = SPECIAL_KEYS[event.key];
	if (special !== undefined) return special;
	const f = /^F(\d{1,2})$/.exec(event.key);
	if (f) return 0xffbd + Number(f[1]);
	const chars = Array.from(event.key);
	if (chars.length !== 1) return null;
	const codePoint = (chars[0] as string).codePointAt(0) as number;
	// Latin-1 keysyms are the characters themselves; the rest of Unicode is 0x01000000 plus the code point.
	return codePoint < 0x100 ? codePoint : 0x01000000 + codePoint;
}

/** Draws a session on a canvas, which takes the desktop's size. */
export function canvasTarget(canvas: HTMLCanvasElement): FrameTarget {
	const context = canvas.getContext("2d", { alpha: false });
	if (!context) throw new Error("This browser can't draw on a canvas");
	return {
		resize(width, height) {
			canvas.width = width;
			canvas.height = height;
		},
		put(x, y, width, height, rgba) {
			if (width > 0 && height > 0)
				context.putImageData(new ImageData(rgba as Uint8ClampedArray<ArrayBuffer>, width, height), x, y);
		},
		copy(fromX, fromY, x, y, width, height) {
			if (width > 0 && height > 0) context.drawImage(canvas, fromX, fromY, width, height, x, y, width, height);
		},
	};
}

/**
 * Where the desktop's picture sits inside a canvas's box on the page. With `object-fit: contain` it's letterboxed in
 * the middle at the desktop's shape; otherwise it fills the box.
 */
export function pictureBox(
	box: { left: number; top: number; width: number; height: number },
	width: number,
	height: number,
	objectFit: string,
): { left: number; top: number; width: number; height: number } {
	if (objectFit !== "contain" || width <= 0 || height <= 0 || box.width <= 0 || box.height <= 0) return box;
	const scale = Math.min(box.width / width, box.height / height);
	const shown = { width: width * scale, height: height * scale };
	return {
		left: box.left + (box.width - shown.width) / 2,
		top: box.top + (box.height - shown.height) / 2,
		...shown,
	};
}

/**
 * Sends a canvas's pointer, wheel and keyboard to a session, scaled from the canvas's size on the page to the desktop.
 * The canvas needs `tabIndex` to take keys. Returns a function that stops it.
 */
export function bindInput(canvas: HTMLCanvasElement, client: VncClient): () => void {
	const listeners = new AbortController();
	const { signal } = listeners;
	let buttons = 0;
	const position = (event: MouseEvent) => {
		const box = canvas.getBoundingClientRect();
		const picture = pictureBox(box, client.width, client.height, getComputedStyle(canvas).objectFit);
		return {
			x: ((event.clientX - picture.left) / picture.width) * client.width,
			y: ((event.clientY - picture.top) / picture.height) * client.height,
		};
	};
	// The browser numbers buttons left, right, middle; VNC numbers them left, middle, right.
	const vncButtons = (pressed: number) => (pressed & 1) | ((pressed & 4) >> 1) | ((pressed & 2) << 1);
	const onPointer = (event: PointerEvent) => {
		buttons = vncButtons(event.buttons);
		const { x, y } = position(event);
		void client.pointer(x, y, buttons);
	};
	canvas.addEventListener("pointermove", onPointer, { signal });
	canvas.addEventListener(
		"pointerdown",
		(event) => {
			canvas.focus();
			canvas.setPointerCapture(event.pointerId);
			onPointer(event);
		},
		{ signal },
	);
	canvas.addEventListener("pointerup", onPointer, { signal });
	canvas.addEventListener("contextmenu", (event) => event.preventDefault(), { signal });
	canvas.addEventListener(
		"wheel",
		(event) => {
			event.preventDefault();
			const { x, y } = position(event);
			const wheel = event.deltaY < 0 ? 8 : event.deltaY > 0 ? 16 : event.deltaX < 0 ? 32 : 64;
			void client.pointer(x, y, buttons | wheel).then(() => client.pointer(x, y, buttons));
		},
		{ signal, passive: false },
	);
	const onKey = (down: boolean) => (event: KeyboardEvent) => {
		const sym = keysym(event);
		if (sym === null) return;
		event.preventDefault();
		void client.key(sym, down);
	};
	canvas.addEventListener("keydown", onKey(true), { signal });
	canvas.addEventListener("keyup", onKey(false), { signal });
	return () => listeners.abort();
}

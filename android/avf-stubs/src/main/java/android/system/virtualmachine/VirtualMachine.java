package android.system.virtualmachine;

import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/** Stub of the system API. The real class is in framework-virtualization.jar. */
public class VirtualMachine implements AutoCloseable {
	public static final int STATUS_STOPPED = 0;
	public static final int STATUS_RUNNING = 1;
	public static final int STATUS_DELETED = 2;
	public static final long MIN_VSOCK_PORT = 1024;

	public String getName() {
		throw new RuntimeException("Stub");
	}

	public int getStatus() {
		throw new RuntimeException("Stub");
	}

	public void setCallback(Executor executor, VirtualMachineCallback callback) {
		throw new RuntimeException("Stub");
	}

	public void clearCallback() {
		throw new RuntimeException("Stub");
	}

	public void run() throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public void stop() throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public VirtualMachineConfig getConfig() {
		throw new RuntimeException("Stub");
	}

	public VirtualMachineConfig setConfig(VirtualMachineConfig config) throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public InputStream getConsoleOutput() throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	/** Hidden on Android 17: needs the process's hidden API exemption. */
	public OutputStream getConsoleInput() throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public InputStream getLogOutput() throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public ParcelFileDescriptor connectVsock(long port) throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	@Override
	public void close() {
		throw new RuntimeException("Stub");
	}
}

package android.system.virtualmachine;

/** Stub of the system API. The real class is in framework-virtualization.jar. */
public class VirtualMachineManager {
	public static final int CAPABILITY_PROTECTED_VM = 1;
	public static final int CAPABILITY_NON_PROTECTED_VM = 2;

	public int getCapabilities() {
		throw new RuntimeException("Stub");
	}

	public VirtualMachine create(String name, VirtualMachineConfig config) throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public VirtualMachine get(String name) throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public VirtualMachine getOrCreate(String name, VirtualMachineConfig config) throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}

	public void delete(String name) throws VirtualMachineException {
		throw new RuntimeException("Stub");
	}
}

package android.system.virtualmachine;

/** Stub of a hidden API. The real class is in framework-virtualization.jar. */
public class VirtualMachineCustomImageConfig {
	public static class Disk {
		public static Disk RWDisk(String imagePath) {
			throw new RuntimeException("Stub");
		}

		public static Disk RODisk(String imagePath) {
			throw new RuntimeException("Stub");
		}
	}

	public static class Builder {
		public Builder() {
			throw new RuntimeException("Stub");
		}

		public Builder setName(String name) {
			throw new RuntimeException("Stub");
		}

		public Builder setKernelPath(String path) {
			throw new RuntimeException("Stub");
		}

		public Builder setInitrdPath(String path) {
			throw new RuntimeException("Stub");
		}

		public Builder setBootloaderPath(String path) {
			throw new RuntimeException("Stub");
		}

		public Builder addParam(String param) {
			throw new RuntimeException("Stub");
		}

		public Builder addDisk(Disk disk) {
			throw new RuntimeException("Stub");
		}

		public Builder useNetwork(boolean enabled) {
			throw new RuntimeException("Stub");
		}

		public Builder useKeyboard(boolean enabled) {
			throw new RuntimeException("Stub");
		}

		public Builder useMouse(boolean enabled) {
			throw new RuntimeException("Stub");
		}

		public Builder useTouch(boolean enabled) {
			throw new RuntimeException("Stub");
		}

		public VirtualMachineCustomImageConfig build() {
			throw new RuntimeException("Stub");
		}
	}
}

package android.system.virtualmachine;

/** Stub of the system API. The real interface is in framework-virtualization.jar. */
public interface VirtualMachineCallback {
	int ERROR_UNKNOWN = 0;
	int STOP_REASON_VIRTUALIZATION_SERVICE_DIED = -1;
	int STOP_REASON_INFRASTRUCTURE_ERROR = 0;
	int STOP_REASON_KILLED = 1;
	int STOP_REASON_UNKNOWN = 2;
	int STOP_REASON_SHUTDOWN = 3;
	int STOP_REASON_START_FAILED = 4;
	int STOP_REASON_REBOOT = 5;
	int STOP_REASON_CRASH = 6;
	int STOP_REASON_HANGUP = 16;

	void onPayloadStarted(VirtualMachine vm);

	void onPayloadReady(VirtualMachine vm);

	void onPayloadFinished(VirtualMachine vm, int exitCode);

	void onError(VirtualMachine vm, int errorCode, String message);

	void onStopped(VirtualMachine vm, int reason);
}

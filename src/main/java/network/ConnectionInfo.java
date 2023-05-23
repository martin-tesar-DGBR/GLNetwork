package network;

public class ConnectionInfo {
	int localSequenceNumber;
	int remoteSequenceNumber;
	AcknowledgmentBuffer ackBuffer;
	ReceiveBuffer receiveBuffer;

	ConnectionInfo(int localSequenceNumber, int remoteSequenceNumber) {
		this.localSequenceNumber = localSequenceNumber;
		this.remoteSequenceNumber = remoteSequenceNumber;
		this.ackBuffer = new AcknowledgmentBuffer(PacketUtils.MAX_PACKETS_IN_FLIGHT, localSequenceNumber);
		this.receiveBuffer = new ReceiveBuffer(PacketUtils.MAX_PACKETS_IN_FLIGHT, remoteSequenceNumber);
	}
}

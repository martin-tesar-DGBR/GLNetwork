package network;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

class ConnectionEndpoint implements Closeable {
	static final long RESEND_DELAY_MS = 500; // 0.5 seconds
	static final int RESEND_COUNT = 4;
	private static final long HEARTBEAT_RESEND_DELAY_MS = 3000; // 3 seconds

	private SocketAddress address;
	private final ConnectionInfo info;
	private Handler handler;
	private Queue<byte[]> sendQueue = new ConcurrentLinkedQueue<>();

	private final DatagramSocket socket;
	private boolean isOpen = true;
	private final Object isOpenLock = new Object();

	private byte[][] chunkedMessageBuffer = new byte[PacketUtils.MAX_NUM_CHUNKS][];
	private int expectedNumChunks = 0;
	private int numChunksReceived = 0;

	private ConnectionNotifier notifier;

	private final Timer timer = new Timer();

	ConnectionEndpoint(DatagramSocket socket, SocketAddress address, int localSeqNum, int remoteSeqNum, Handler handler) {
		this(socket, address, new ConnectionInfo(localSeqNum, remoteSeqNum), handler);
	}

	public ConnectionEndpoint(DatagramSocket socket, SocketAddress address, ConnectionInfo info, Handler handler) {
		this.socket = socket;
		this.address = address;
		this.info = info;
		this.handler = handler;
	}

	void setNotifier(ConnectionNotifier notifier) {
		this.notifier = notifier;
	}

	void sendReliable(byte[] data) {
		synchronized (isOpenLock) {
			if (!isOpen) {
				return;
			}
		}
		if (data.length > PacketUtils.MAX_DATA_PER_CHUNK) {
			if (data.length > PacketUtils.MAX_PAYLOAD_SIZE) {
				throw new IllegalArgumentException("Payload size " + data.length + " is too large to be sent.");
			}
			byte[][] payloads;
			synchronized (info) {
				payloads = PacketUtils.constructReliableChunkedPackets(data, info.localSequenceNumber);
				info.localSequenceNumber += payloads.length;
			}
			for (int i = 0; i < payloads.length; i++) {
				queueMessage(payloads[i]);
			}
		}
		else {
			byte[] payload;
			synchronized (info) {
				payload = PacketUtils.constructReliablePacket(data, info.localSequenceNumber, info.remoteSequenceNumber);
				info.localSequenceNumber++;
			}
			queueMessage(payload);
		}
	}

	void sendReliablePayload(byte[] payload) {
		synchronized (isOpenLock) {
			if (!isOpen) {
				return;
			}
		}
		synchronized (info) {
			info.localSequenceNumber++;
		}
		queueMessage(payload);
	}

	private void queueMessage(byte[] payload) {
		if (info.ackBuffer.isFull() || !sendQueue.isEmpty()) {
			sendQueue.add(payload);
			return;
		}
		try {
			sendReliableNoBufferCheck(payload);
		} catch (SocketException e) {
			close();
		} catch (IOException e) {
			e.printStackTrace();
			close();
		}
	}

	void handlePacket(byte[] data) {
		if (!PacketUtils.isValidPacket(data)) {
			return;
		}
		byte flags = PacketUtils.getFlags(data);
		if ((flags & PacketUtils.SYN_MASK) == PacketUtils.SYN_MASK ||
			(flags & (PacketUtils.SYN_MASK | PacketUtils.ACK_MASK)) == (PacketUtils.SYN_MASK | PacketUtils.ACK_MASK)) {
			return;
		}
		else if ((flags & (PacketUtils.SYN_MASK | PacketUtils.ACK_MASK)) == (PacketUtils.ACK_MASK)) {
			handleAck(data);
		}
		else if ((flags & (PacketUtils.ACK_MASK | PacketUtils.RELIABLE_MASK)) == 0) {
			processRawPacket(data);
		}
		else {
			processReliablePacket(data);
		}
	}

	private void handleAck(byte[] data) {
		if (PacketUtils.isFINACK(data)) {
			close();
		}
		int ackNumber = PacketUtils.getAckNum(data);
		if (!info.ackBuffer.isAcked(ackNumber)) {
			info.ackBuffer.signalAck(ackNumber);
		}
		//if there is more data to be sent in the send queue, do it
		while (!sendQueue.isEmpty() && !info.ackBuffer.isFull()) {
			byte[] next = sendQueue.poll();
			try {
				sendReliableNoBufferCheck(next);
			} catch (IOException e) {
				e.printStackTrace();
				close();
			}
		}
	}

	private void processRawPacket(byte[] data) {
		byte[] userData = new byte[data.length - PacketUtils.HEADER_SIZE];
		System.arraycopy(data, PacketUtils.HEADER_SIZE, userData, 0, userData.length);
		handler.onReceive(address, userData);
	}

	private void processFragmentedPacket(byte[] data) {
		int numChunks = PacketUtils.getNumChunks(data);
		if (numChunksReceived == expectedNumChunks) {
			expectedNumChunks = numChunks;
			numChunksReceived = 0;
		}

		if (expectedNumChunks == numChunks) {
			int chunkIndex = PacketUtils.getChunkIndex(data);
			chunkedMessageBuffer[chunkIndex] = data;
			numChunksReceived++;
		}
		else {
			throw new IllegalStateException("Received chunk not associated with current data.");
		}

		if (numChunksReceived == expectedNumChunks) {
			byte[] userData = PacketUtils.assembleDataFromChunks(chunkedMessageBuffer, expectedNumChunks);
			handler.onReceive(address, userData);
		}
	}

	private void processReliablePacket(byte[] data) {
		int seqNum = PacketUtils.getSeqNum(data);
		if (info.receiveBuffer.inRange(seqNum) && !info.receiveBuffer.isOccupied(seqNum)) {
			info.receiveBuffer.add(data);
		}
		if (!PacketUtils.sequenceGreaterThan(seqNum, info.receiveBuffer.getMaxExpectedSequenceNumber()) && !PacketUtils.isFIN(data)) {
			ackRemotePacket(seqNum);
		}
		if (seqNum == info.receiveBuffer.getExpectedSequenceNumber()) {
			byte[][] bufferedData = info.receiveBuffer.flush();
			for (int i = 0; i < bufferedData.length; i++) {
				byte flags = PacketUtils.getFlags(bufferedData[i]);
				if ((flags & (PacketUtils.RELIABLE_MASK | PacketUtils.FIN_MASK)) == (PacketUtils.RELIABLE_MASK | PacketUtils.FIN_MASK)) {
					sendFINACK(bufferedData[i]);
					break;
				}
				if ((flags & (PacketUtils.RELIABLE_MASK | PacketUtils.HEARTBEAT_MASK)) == (PacketUtils.RELIABLE_MASK | PacketUtils.HEARTBEAT_MASK)) {
					continue;
				}
				if ((flags & (PacketUtils.CHUNKED_MASK)) == PacketUtils.CHUNKED_MASK) {
					processFragmentedPacket(bufferedData[i]);
				}
				else {
					processRawPacket(bufferedData[i]);
				}
			}
		}
	}

	private void ackRemotePacket(int seqNum) {
		byte[] ackData = PacketUtils.constructACKPacket(info.localSequenceNumber, seqNum);
		DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, address);
		try {
			sendRaw(ackPacket);
		} catch (SocketException e) {
			close();
		} catch (IOException e) {
			e.printStackTrace();
			close();
		}
	}

	private void sendFINACK(byte[] data) {
		int finSeqNum = PacketUtils.getSeqNum(data);
		byte[] finAckData = PacketUtils.constructFINACKPacket(info.localSequenceNumber, finSeqNum);
		DatagramPacket finAckPacket = new DatagramPacket(finAckData, finAckData.length, address);
		try {
			sendRaw(finAckPacket);
		} catch (IOException e) {
			close();
		}
		close();
	}

	private synchronized void sendReliableNoBufferCheck(byte[] payload) throws IOException {
		int seqNum = PacketUtils.getSeqNum(payload);
		info.ackBuffer.add(payload);

		DatagramPacket packet = new DatagramPacket(payload, payload.length, address);
		sendRaw(packet);

		synchronized (timer) {
			timer.schedule(ackTimeout(seqNum), RESEND_DELAY_MS);
		}
	}

	private TimerTask ackTimeout(int seqNum) {
		return new TimerTask() {
			@Override
			public void run() {
				synchronized (isOpenLock) {
					if (!isOpen) {
						return;
					}
					if (info.ackBuffer.isAcked(seqNum)) {
						return;
					}
					if (info.ackBuffer.getTimesAccessed(seqNum) > RESEND_COUNT) {
						close();
					}
					byte[] payload = info.ackBuffer.findData(seqNum);
					DatagramPacket packet = new DatagramPacket(payload, payload.length, address);
					try {
						sendRaw(packet);
						if (isOpen) {
							synchronized (timer) {
								timer.schedule(ackTimeout(seqNum), RESEND_DELAY_MS);
							}
						}
					} catch (SocketException e) {
						close();
					} catch (IOException e) {
						e.printStackTrace();
						close();
					}
				}
			}
		};
	}

	void startHeartbeat() {
		synchronized (timer) {
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					synchronized (isOpenLock) {
						if (!isOpen) {
							return;
						}
						sendHeartbeat();
					}
				}
			}, 0, HEARTBEAT_RESEND_DELAY_MS);
		}
	}

	private void sendHeartbeat() {
		byte[] packet;
		synchronized (info) {
			packet = PacketUtils.constructHeartbeatPacket(info.localSequenceNumber, info.remoteSequenceNumber);
		}
		sendReliablePayload(packet);
	}

	void sendRaw(DatagramPacket packet) throws IOException {
		synchronized (socket) {
			socket.send(packet);
		}
	}

	int getExpectedSequenceNumber() {
		return this.info.ackBuffer.getExpectedSequenceNumber();
	}

	int getRemoteSequenceNumber() {
		synchronized (info) {
			return this.info.remoteSequenceNumber;
		}
	}

	void disconnect() {
		byte[] finPacket;
		synchronized (info) {
			finPacket = PacketUtils.constructFINPacket(info.localSequenceNumber, info.remoteSequenceNumber);
			info.localSequenceNumber++;
		}
		queueMessage(finPacket);
	}

	@Override
	public synchronized void close() {
		synchronized (isOpenLock) {
			if (!isOpen) {
				return;
			}
			isOpen = false;
			synchronized (timer) {
				timer.cancel();
			}
			if (notifier != null) {
				notifier.onDisconnect(address);
			}
			handler.onDisconnect(address);
		}
	}

	boolean isOpen() {
		synchronized (isOpenLock) {
			return isOpen;
		}
	}

	public SocketAddress getAddress() {
		return this.address;
	}
}

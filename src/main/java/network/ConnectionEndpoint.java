package network;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

class ConnectionEndpoint implements Closeable {
	static final long RESEND_DELAY_MS = 500; // 0.5 seconds
	static final int RESEND_COUNT = 4;

	SocketAddress address;
	ConnectionInfo info;
	Handler handler;
	Queue<byte[]> sendQueue = new ConcurrentLinkedQueue<>();

	DatagramSocket socket;
	boolean isOpen = true;
	final Object isOpenLock = new Object();

	ConnectionNotifier notifier;

	Timer missingAckScheduler = new Timer();

	ConnectionEndpoint(DatagramSocket socket, SocketAddress address, int localSeqNum, int remoteSeqNum, Handler handler) {
		this.socket = socket;
		this.address = address;
		this.info = new ConnectionInfo(localSeqNum, remoteSeqNum);
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
		byte[] payload;
		synchronized (info) {
			payload = PacketUtils.constructReliablePacket(data, info.localSequenceNumber, info.remoteSequenceNumber);
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

	private void processReliablePacket(byte[] data) {
		int seqNum = PacketUtils.getSeqNum(data);
		if (info.receiveBuffer.inRange(seqNum) && !info.receiveBuffer.isOccupied(seqNum)) {
			info.receiveBuffer.add(data);
		}
		if (!PacketUtils.sequenceGreaterThan(seqNum, info.receiveBuffer.getMaxExpectedSequenceNumber()) && !PacketUtils.isFIN(data)) {
			byte[] ackData = PacketUtils.constructACKPacket(info.localSequenceNumber, seqNum);
			DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, address);
			try {
				sendRaw(ackPacket);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (seqNum == info.receiveBuffer.getExpectedSequenceNumber()) {
			byte[][] bufferedData = info.receiveBuffer.flush();
			for (int i = 0; i < bufferedData.length; i++) {
				if (PacketUtils.isFIN(bufferedData[i])) {
					sendFINACK(bufferedData[i]);
					break;
				}
				processRawPacket(bufferedData[i]);
			}
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

		synchronized (missingAckScheduler) {
			missingAckScheduler.schedule(ackTimeout(seqNum), RESEND_DELAY_MS);
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
							synchronized (missingAckScheduler) {
								missingAckScheduler.schedule(ackTimeout(seqNum), RESEND_DELAY_MS);
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

	void sendRaw(DatagramPacket packet) throws IOException {
		synchronized (socket) {
			socket.send(packet);
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
			synchronized (missingAckScheduler) {
				missingAckScheduler.cancel();
			}
			if (notifier != null) {
				notifier.onDisconnect(address);
			}
			handler.onDisconnect(address);
		}
	}
}

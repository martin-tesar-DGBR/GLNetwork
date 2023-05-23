package network;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;

public class Client implements ConnectionNotifier, Closeable{
	private static final int STATE_SYN = 0;
	private static final int STATE_SYN_ACK = 1;
	private static final int STATE_ACK = 2;
	private static final int STATE_COMPLETE = 3;

	SocketAddress address;
	boolean isOpen = false;
	final Object isOpenLock = new Object();

	DatagramSocket connectionSocket;
	ConnectionEndpoint endpoint;

	ClientHandler handler;

	public Client(InetAddress address, int port, ClientHandler handler) {
		this.address = new InetSocketAddress(address, port);
		this.handler = handler;
		this.handler.setClient(this);
	}

	public void connect() throws IOException {
		this.connectionSocket = new DatagramSocket();
		connectionSocket.setSoTimeout((int) ConnectionEndpoint.RESEND_DELAY_MS);
		int[] sequenceNumbers = new int[2]; //first index local sequence number to start, second index remote
		boolean hasConnected = connectToRemote(sequenceNumbers);
		if (!hasConnected) {
			connectionSocket.close();
			return;
		}
		connectionSocket.setSoTimeout(0);
		endpoint = new ConnectionEndpoint(connectionSocket, address, sequenceNumbers[0], sequenceNumbers[1], handler);
		endpoint.setNotifier(this);
		isOpen = true;
		handler.onConnect(address);

		Thread recvThread = new Thread(this::listen);
		recvThread.setDaemon(true);
		recvThread.start();
	}

	private boolean connectToRemote(int[] sequenceNumbers) throws IOException {
		int localSequenceNumber = 0;
		int remoteSequenceNumber = -1;

		int incorrectReceive = 0;

		int state = STATE_SYN;
		while (state != STATE_COMPLETE) {
			switch (state) {
				case STATE_SYN -> {
					if (incorrectReceive >= ConnectionEndpoint.RESEND_COUNT) {
						return false;
					}
					byte[] synPacket = PacketUtils.constructSYNPacket(localSequenceNumber);
					DatagramPacket connectionPacket = new DatagramPacket(synPacket, synPacket.length, address);
					try {
						connectionSocket.send(connectionPacket);
					} catch (IOException e) {
						e.printStackTrace();
					}

					state = STATE_SYN_ACK;
				}
				case STATE_SYN_ACK -> {
					DatagramPacket ackPacket = new DatagramPacket(new byte[PacketUtils.MAX_PACKET_SIZE], PacketUtils.MAX_PACKET_SIZE);
					try {
						connectionSocket.receive(ackPacket);
					} catch (SocketTimeoutException e) {
						incorrectReceive++;
						state = STATE_SYN;
						continue;
					}
					byte[] data = new byte[ackPacket.getLength()];
					System.arraycopy(ackPacket.getData(), ackPacket.getOffset(), data, 0, data.length);
					int seqNum = PacketUtils.getSeqNum(data);
					int ackNum = PacketUtils.getAckNum(data);
					if (data[0] == PacketUtils.VERSION_ID) {
						if (PacketUtils.isSYNACK(data) &&
							ackNum == localSequenceNumber) {
							state = STATE_ACK;
							localSequenceNumber++;
							remoteSequenceNumber = seqNum;
						}
						else {
							state = STATE_SYN;
							incorrectReceive++;
						}
					}
				}
				case STATE_ACK -> {
					byte[] ackPacket = PacketUtils.constructACKPacket(localSequenceNumber, remoteSequenceNumber);
					DatagramPacket connectionPacket = new DatagramPacket(
						ackPacket, ackPacket.length, address);
					connectionSocket.send(connectionPacket);
					sequenceNumbers[0] = localSequenceNumber;
					sequenceNumbers[1] = remoteSequenceNumber;
					state = STATE_COMPLETE;
				}
				default -> {
					throw new IllegalStateException("In undefined state: " + state);
				}
			}
		}
		return true;
	}

	private void listen() {
		byte[] buffer = new byte[PacketUtils.MAX_PACKET_SIZE];
		DatagramPacket recvPacket = new DatagramPacket(buffer, PacketUtils.MAX_PACKET_SIZE);

		while (isOpen) {
			try {
				connectionSocket.receive(recvPacket);
			} catch (SocketException e) {
				synchronized (isOpenLock) {
					isOpen = false;
				}
				break;
			} catch (IOException e) {
				e.printStackTrace();
				synchronized (isOpenLock) {
					isOpen = false;
				}
				break;
			}
			byte[] data = new byte[recvPacket.getLength()];
			System.arraycopy(recvPacket.getData(), recvPacket.getOffset(), data, 0, data.length);
			endpoint.handlePacket(data);
		}
	}

	public void sendReliable(byte[] data) {
		synchronized (isOpenLock) {
			if (!isOpen) {
				throw new IllegalStateException("Connection is closed.");
			}
		}
		endpoint.sendReliable(data);
	}

	public void sendRaw(byte[] data) {
		byte[] header = PacketUtils.constructUnreliablePacket(data);
		DatagramPacket packet = new DatagramPacket(header, header.length, address);
		try {
			endpoint.sendRaw(packet);
		} catch (SocketException e) {
			close();
		} catch (IOException e) {
			e.printStackTrace();
			close();
		}
	}

	public void disconnect() {
		endpoint.disconnect();
	}

	public boolean isOpen() {
		synchronized (isOpenLock) {
			return isOpen;
		}
	}

	@Override
	public void onDisconnect(SocketAddress address) {
		connectionSocket.close();
		synchronized (isOpenLock) {
			isOpen = false;
		}
	}

	@Override
	public void close() {
		synchronized (isOpenLock) {
			if (!isOpen) {
				return;
			}
		}
		if (endpoint != null && endpoint.isOpen) {
			endpoint.close();
		}
		onDisconnect(address);
	}

	public SocketAddress getLocalAddress() {
		if (!isOpen) {
			throw new IllegalStateException("Connection is closed.");
		}
		return connectionSocket.getLocalSocketAddress();
	}
}

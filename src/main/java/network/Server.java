package network;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Server implements ConnectionNotifier, Closeable{
	int port;
	boolean isOpen = false;
	final Object isOpenLock = new Object();

	DatagramSocket connectionSocket;
	Map<SocketAddress, ConnectionEndpoint> connections = new ConcurrentHashMap<>();
	Map<SocketAddress, PendingConnection> pendingConnections = new ConcurrentHashMap<>();
	int numConnections = 0;
	final Object numConnectionsLock = new Object();
	ServerHandler handler;

	Timer pendingConnectionTimer = new Timer();

	public Server(int port, ServerHandler handler) {
		this.port = port;
		this.handler = handler;
		this.handler.setServer(this);
	}

	public void start() throws IOException {
		this.connectionSocket = new DatagramSocket(port);
		isOpen = true;
		Thread recvThread = new Thread(this::listen);
		recvThread.setDaemon(true);
		recvThread.start();
	}

	private void listen() {
		byte[] buffer = new byte[PacketUtils.MAX_PACKET_SIZE];
		DatagramPacket recvPacket = new DatagramPacket(buffer, PacketUtils.MAX_PACKET_SIZE);
		while (true) {
			synchronized (isOpenLock) {
				if (!isOpen) {
					break;
				}
			}
			try {
				this.connectionSocket.receive(recvPacket);
			} catch (SocketException e) {
				//handle close() being called on the socket while it's receiving, which is expected behaviour
				close();
				break;
			} catch (IOException e) {
				e.printStackTrace();
				close();
				break;
			}
			SocketAddress recvAddress = recvPacket.getSocketAddress();
			byte[] data = new byte[recvPacket.getLength()];
			System.arraycopy(recvPacket.getData(), recvPacket.getOffset(), data, 0, data.length);
			if (!PacketUtils.isValidPacket(data) ||
				PacketUtils.isSYNACK(data)) {
				continue;
			}

			if (PacketUtils.isSYN(data)) {
				establishNewConnection(recvAddress, data);
			}
			else {
				if (PacketUtils.isACK(data) && pendingConnections.containsKey(recvPacket.getSocketAddress())) {
					respondToAck(recvAddress, data);
				}
				else {
					ConnectionEndpoint endpoint = connections.get(recvAddress);
					if (endpoint != null) {
						endpoint.handlePacket(data);
					}
				}
			}
		}
	}

	private void establishNewConnection(SocketAddress recvAddress, byte[] data) {
		if (connections.containsKey(recvAddress) || pendingConnections.containsKey(recvAddress)) {
			return;
		}
		byte versionID = data[0];
		if (versionID != PacketUtils.VERSION_ID) {
			return;
		}
		int remoteSeqNum = PacketUtils.getSeqNum(data);
		int thisSeqNum = 420;
		byte[] packetData = PacketUtils.constructSYNACKPacket(thisSeqNum, remoteSeqNum);
		DatagramPacket response = new DatagramPacket(packetData, packetData.length, recvAddress);
		try {
			sendRaw(response);
		} catch (IOException e) {
			e.printStackTrace();
		}
		pendingConnections.put(recvAddress, new PendingConnection(new int[]{thisSeqNum, remoteSeqNum}));
		synchronized (pendingConnectionTimer) {
			pendingConnectionTimer.schedule(synackTimer(recvAddress, response), ConnectionEndpoint.RESEND_DELAY_MS);
		}
	}

	private TimerTask synackTimer(SocketAddress recvAddress, DatagramPacket response) {
		return new TimerTask() {
			@Override
			public void run() {
				PendingConnection connection = pendingConnections.get(recvAddress);
				if (connection == null) {
					return;
				}
				if (connection.timeoutCount >= ConnectionEndpoint.RESEND_COUNT) {
					pendingConnections.remove(recvAddress);
					return;
				}
				connection.timeoutCount++;
				try {
					sendRaw(response);
				} catch (IOException e) {
					e.printStackTrace();
				}
				synchronized (pendingConnectionTimer) {
					pendingConnectionTimer.schedule(synackTimer(recvAddress, response), ConnectionEndpoint.RESEND_DELAY_MS);
				}
			}
		};
	}

	private void respondToAck(SocketAddress recvAddress, byte[] data) {
		if (connections.containsKey(recvAddress)) {
			return;
		}
		int[] pendingInfo = pendingConnections.get(recvAddress).sequenceNumbers;
		if (pendingInfo == null) {
			return;
		}
		int seqNum = PacketUtils.getSeqNum(data);
		int ackNum = PacketUtils.getAckNum(data);
		if (ackNum == pendingInfo[0] && seqNum == pendingInfo[1] + 1) {
			ConnectionEndpoint endpoint = new ConnectionEndpoint(connectionSocket, recvAddress, pendingInfo[0], seqNum, handler);
			endpoint.setNotifier(this);
			connections.put(recvAddress, endpoint);
			pendingConnections.remove(recvAddress);
			synchronized (numConnectionsLock) {
				numConnections++;
			}
			handler.onConnect(recvAddress);
		}
	}

	public void sendAllRaw(byte[] data) {
		byte[] header = PacketUtils.constructUnreliablePacket(data);
		for (ConnectionEndpoint endpoint : connections.values()) {
			DatagramPacket packet = new DatagramPacket(header, header.length, endpoint.address);
			try {
				endpoint.sendRaw(packet);
			} catch (SocketException e) {
				endpoint.close();
			} catch (IOException e) {
				e.printStackTrace();
				endpoint.close();
			}
		}
	}

	public void sendAllReliable(byte[] data) {
		for (ConnectionEndpoint endpoint : connections.values()) {
			endpoint.sendReliable(data);
		}
	}

	public void sendRaw(SocketAddress address, byte[] data) {
		byte[] header = PacketUtils.constructUnreliablePacket(data);
		DatagramPacket packet = new DatagramPacket(header, header.length, address);
		ConnectionEndpoint endpoint = connections.get(address);
		try {
			endpoint.sendRaw(packet);
		} catch (SocketException e) {
			endpoint.close();
		} catch (IOException e) {
			e.printStackTrace();
			endpoint.close();
		}
	}

	private synchronized void sendRaw(DatagramPacket response) throws IOException {
		synchronized (connectionSocket) {
			connectionSocket.send(response);
		}
	}

	public void sendReliable(SocketAddress dst, byte[] data) {
		ConnectionEndpoint endpoint = connections.get(dst);
		endpoint.sendReliable(data);
	}

	public void disconnect(SocketAddress dst) {
		ConnectionEndpoint endpoint = connections.get(dst);
		endpoint.disconnect();
	}

	public boolean isOpen() {
		synchronized (isOpenLock) {
			return isOpen;
		}
	}

	public int getNumConnections() {
		synchronized (numConnectionsLock) {
			return numConnections;
		}
	}

	@Override
	public void onDisconnect(SocketAddress address) {
		connections.remove(address);
		pendingConnections.remove(address);
		synchronized (numConnectionsLock) {
			numConnections--;
		}
	}

	@Override
	public void close() {
		synchronized (isOpenLock) {
			if (!isOpen) {
				return;
			}
			isOpen = false;
		}
		this.connectionSocket.close();
		connections.clear();
		pendingConnections.clear();
	}

	private static class PendingConnection {
		int[] sequenceNumbers;
		int timeoutCount;

		PendingConnection(int[] sequenceNumbers) {
			this.sequenceNumbers = sequenceNumbers;
			this.timeoutCount = 0;
		}
	}
}

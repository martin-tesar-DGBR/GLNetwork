package network;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Server implements ConnectionNotifier, Closeable {
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

	public Server(int port) {
		this.port = port;
	}

	public Server(int port, ServerHandler handler) {
		this.port = port;
		this.handler = handler;
		this.handler.setServer(this);
	}

	public void setHandler(ServerHandler handler) {
		this.handler = handler;
		this.handler.setServer(this);
	}

	public void start() throws IOException {
		if (this.handler == null) {
			throw new IllegalStateException("No handler set.");
		}
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

			byte flags = PacketUtils.getFlags(data);
			if ((flags & (PacketUtils.SYN_MASK | PacketUtils.ACK_MASK)) == PacketUtils.SYN_MASK) {
				establishNewConnection(recvAddress, data);
			}
			else {
				if ((flags & (PacketUtils.SYN_MASK | PacketUtils.ACK_MASK)) == PacketUtils.ACK_MASK && pendingConnections.containsKey(recvAddress)) {
					respondToAck(recvAddress, data);
				}
				else {
					ConnectionEndpoint endpoint;
					if (connections.containsKey(recvAddress)) {
						endpoint = connections.get(recvAddress);
						endpoint.handlePacket(data);
					}
					else if (pendingConnections.containsKey(recvAddress)) {
						endpoint = pendingConnections.get(recvAddress).endpoint;
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
		PendingConnection newConnection = new PendingConnection(recvAddress, thisSeqNum, remoteSeqNum + 1);
		newConnection.endpoint.sendReliablePayload(packetData);
		pendingConnections.put(recvAddress, newConnection);

		synchronized (pendingConnectionTimer) {
			pendingConnectionTimer.schedule(synackTimer(recvAddress, packetData), ConnectionEndpoint.RESEND_DELAY_MS);
		}
	}

	private TimerTask synackTimer(SocketAddress recvAddress, byte[] synAckResponse) {
		return new TimerTask() {
			@Override
			public void run() {
				PendingConnection connection = pendingConnections.get(recvAddress);
				if (connection == null) {
					return;
				}
				if (connection.timeoutCount >= ConnectionEndpoint.RESEND_COUNT) {
					pendingConnections.remove(recvAddress);
					connection.endpoint.close();
					return;
				}
				connection.timeoutCount++;
				DatagramPacket response = new DatagramPacket(synAckResponse, synAckResponse.length, recvAddress);
				try {
					sendRaw(response);
				} catch (IOException e) {
					e.printStackTrace();
					connection.endpoint.close();
					return;
				}
				synchronized (pendingConnectionTimer) {
					pendingConnectionTimer.schedule(synackTimer(recvAddress, synAckResponse), ConnectionEndpoint.RESEND_DELAY_MS);
				}
			}
		};
	}

	private void respondToAck(SocketAddress recvAddress, byte[] data) {
		if (connections.containsKey(recvAddress)) {
			return;
		}
		ConnectionEndpoint pendingEndpoint = pendingConnections.get(recvAddress).endpoint;
		if (pendingEndpoint == null) {
			return;
		}
		int seqNum = PacketUtils.getSeqNum(data);
		int ackNum = PacketUtils.getAckNum(data);
		if (ackNum == pendingEndpoint.getExpectedSequenceNumber() && seqNum == pendingEndpoint.getRemoteSequenceNumber()) {
			pendingEndpoint.handlePacket(data);
			pendingEndpoint.setNotifier(this);
			connections.put(recvAddress, pendingEndpoint);
			pendingConnections.remove(recvAddress);
			synchronized (numConnectionsLock) {
				numConnections++;
			}
			pendingEndpoint.startHeartbeat();
			handler.onConnect(recvAddress);
		}
	}

	public void sendAllRaw(byte[] data) {
		byte[] header = PacketUtils.constructUnreliablePacket(data);
		for (ConnectionEndpoint endpoint : connections.values()) {
			DatagramPacket packet = new DatagramPacket(header, header.length, endpoint.getAddress());
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
		if (endpoint != null) {
			endpoint.sendReliable(data);
		}
	}

	public void disconnect(SocketAddress dst) {
		ConnectionEndpoint endpoint = connections.get(dst);
		if (endpoint != null) {
			endpoint.disconnect();
		}
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

	private class PendingConnection {
		ConnectionEndpoint endpoint;
		int timeoutCount;

		PendingConnection(SocketAddress address, int localSeqNum, int remoteSeqNum) {
			endpoint = new ConnectionEndpoint(connectionSocket, address, localSeqNum, remoteSeqNum, handler);
			this.timeoutCount = 0;
		}
	}
}

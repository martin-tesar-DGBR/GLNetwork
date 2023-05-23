package network;

import java.net.SocketAddress;

public abstract class ServerHandler implements Handler {
	Server server;

	void setServer(Server server) {
		this.server = server;
	}

	public void sendRaw(SocketAddress address, byte[] data) {
		server.sendRaw(address, data);
	}

	public void sendReliable(SocketAddress address, byte[] data) {
		server.sendReliable(address, data);
	}

	public void sendAllRaw(byte[] data) {
		server.sendAllRaw(data);
	}

	public void sendAllReliable(byte[] data) {
		server.sendAllReliable(data);
	}

	public void disconnect(SocketAddress address) {
		server.disconnect(address);
	}
}

package network;

import java.net.SocketAddress;

public interface Handler {
	void onConnect(SocketAddress address);
	void onDisconnect(SocketAddress address);
	void onReceive(SocketAddress address, byte[] data);
}

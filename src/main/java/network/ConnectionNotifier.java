package network;

import java.net.SocketAddress;

/**
 * Interface for a connection that wishes to be notified of changes to a ConnectionEndpoint.
 */
public interface ConnectionNotifier {
	void onDisconnect(SocketAddress address);
}

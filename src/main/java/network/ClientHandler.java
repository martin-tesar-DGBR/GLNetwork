package network;

public abstract class ClientHandler implements Handler {
	Client client;

	void setClient(Client client) {
		this.client = client;
	}

	public void sendRaw(byte[] data) {
		client.sendRaw(data);
	}

	public void sendReliable(byte[] data) {
		client.sendReliable(data);
	}

	public void disconnect() {
		client.disconnect();
	}
}

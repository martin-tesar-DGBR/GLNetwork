import network.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ClientServerTest {
	@Test
	void pingPong() {
		System.out.println("=== PING PONG ===");
		int bounceLimit = 16;
		final int[] receiveCountServer = {0};
		final int[] receiveCountClient = {0};
		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {
				System.out.println("[SERVER] Connected " + address);
			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("Server disconnected");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				synchronized (receiveCountServer) {
					receiveCountServer[0]++;
					if (receiveCountServer[0] <= bounceLimit && receiveCountServer[0] >= 0) {
						System.out.println("Server received " + receiveCountServer[0]);
					}
					if (receiveCountServer[0] > bounceLimit) {
						disconnect(address);
						System.out.println("Called disconnect from server");
					}
				}
				String received = new String(data, 0, data.length, StandardCharsets.UTF_8);
				int num = Integer.parseInt(received);
				String send = String.valueOf(num + 1);
				sendReliable(address, send.getBytes(StandardCharsets.UTF_8));
			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {
				System.out.println("Client connected, sending 0");
				String send = "0";
				sendReliable(send.getBytes(StandardCharsets.UTF_8));
			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("Client disconnected.");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				synchronized (receiveCountClient) {
					receiveCountClient[0]++;
					if (receiveCountClient[0] <= bounceLimit && receiveCountClient[0] >= 0) {
						System.out.println("Client received " + receiveCountClient[0]);
					}
				}
				String received = new String(data, 0, data.length, StandardCharsets.UTF_8);
				int num = Integer.parseInt(received);
				String send = String.valueOf(num + 1);
				sendReliable(send.getBytes(StandardCharsets.UTF_8));
			}
		};

		try	(Server server = new Server(2678, serverHandler);
			 Client client = new Client(InetAddress.getLoopbackAddress(), 2678, clientHandler)) {
			server.start();
			client.connect();
			while (client.isOpen()) {
				Thread.sleep(1000);
			}
			System.out.println("Done\n");

		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void sendInfoStreams() {
		System.out.println("=== SEND INFO STREAMS ===");
		int receiveLimit = 40;
		final int[] receiveCountServer = {0};
		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {
				for (int i = 0; i < receiveLimit; i++) {
					sendReliable(address, String.valueOf(i + 1).getBytes(StandardCharsets.UTF_8));
				}
			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("Server was disconnected.");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				synchronized (receiveCountServer) {
					receiveCountServer[0]++;
					if (receiveCountServer[0] >= receiveLimit) {
						System.out.println("Called disconnect at " + receiveCountServer[0]);
						disconnect(address);
					}
				}
				String s = new String(data, 0, data.length, StandardCharsets.UTF_8);
				System.out.println("[SERVER] Received: " + s);
			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("Client was disconnected.");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				String s = new String(data, 0, data.length, StandardCharsets.UTF_8);
				System.out.println("[CLIENT] Received: " + s);
			}
		};

		try	(Server server = new Server(2678, serverHandler); Client client = new Client(InetAddress.getLocalHost(), 2678, clientHandler)) {
			server.start();
			client.connect();
			for (int i = 0; i < receiveLimit; i++) {
				client.sendReliable(String.valueOf(i + 1).getBytes(StandardCharsets.UTF_8));
			}
			while (client.isOpen()) {
				Thread.sleep(3000);
			}
			System.out.println("Done\n");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void multipleClients() {
		System.out.println("=== MULTIPLE CLIENTS ===");
		ServerHandler serverHandler = new ServerHandler() {
			Set<SocketAddress> connectedClients = Collections.synchronizedSet(new HashSet<>());

			@Override
			public void onConnect(SocketAddress address) {
				connectedClients.add(address);
				System.out.println("[SERVER] added endpoint: " + address);
				sendAllReliable(("Client " + address + " has connected.").getBytes(StandardCharsets.UTF_8));
			}

			@Override
			public void onDisconnect(SocketAddress address) {
				connectedClients.remove(address);
				System.out.println("[SERVER] removed endpoint: " + address);
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {

			}
		};

		ClientHandler clientHandler1 = new ClientHandler() {
			int count = 0;

			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[CLIENT 1] Disconnected");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				String s = new String(data, StandardCharsets.UTF_8);
				System.out.println("[CLIENT 1] Received " + s);
				count++;
				if (count > 5) {
					disconnect();
				}
				else {
					sendReliable("received".getBytes(StandardCharsets.UTF_8));
				}
			}
		};

		ClientHandler clientHandler2 = new ClientHandler() {
			int count = 0;

			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[CLIENT 2] Disconnected");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				String s = new String(data, StandardCharsets.UTF_8);
				System.out.println("[CLIENT 2] Received " + s);
				count++;
				if (count > 10) {
					disconnect();
				}
				else {
					sendReliable("received".getBytes(StandardCharsets.UTF_8));
				}
			}
		};

		InetAddress address = InetAddress.getLoopbackAddress();

		try (Server server = new Server(2678, serverHandler);
			 Client client1 = new Client(address, 2678, clientHandler1);
			 Client client2 = new Client(address, 2678, clientHandler2)) {
			server.start();
			client1.connect();
			client2.connect();
			int count = 0;
			byte[] heartbeat = "ba dum".getBytes(StandardCharsets.UTF_8);
			if (client1.isOpen()) {
				client1.sendReliable(heartbeat);
				System.out.println("[CLIENT 1] Opened at " + client1.getLocalAddress());
			}
			else {
				System.out.println("Client 1 failed to open.");
			}
			if (client2.isOpen()) {
				client2.sendReliable(heartbeat);
				System.out.println("[CLIENT 2] Opened at " + client2.getLocalAddress());
			}
			else {
				System.out.println("Client 2 failed to open.");
			}
			while (client1.isOpen() || client2.isOpen()) {
				Thread.sleep(500);
				System.out.println("[SERVER] Sending: " + count);
				server.sendAllReliable(String.valueOf(count).getBytes(StandardCharsets.UTF_8));
				count++;
			}
			System.out.println("Done\n");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void improperDisconnectClient() {
		System.out.println("=== IMPROPER DC CLIENT ===");
		final boolean[] clientShouldClose = {false};
		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[SERVER] Disconnected.");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {

			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[CLIENT] Disconnected.");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				String s = new String(data, StandardCharsets.UTF_8);
				System.out.println("[CLIENT] Received " + s);
				int result = Integer.parseInt(s);
				if (result > 6) {
					synchronized (clientShouldClose) {
						clientShouldClose[0] = true;
					}
				}
			}
		};

		InetAddress address = InetAddress.getLoopbackAddress();

		try (Server server = new Server(2678, serverHandler);
			 Client client = new Client(address, 2678, clientHandler)) {
			server.start();
			client.connect();
			int count = 0;
			while (server.getNumConnections() > 0) {
				Thread.sleep(500);
				synchronized (clientShouldClose) {
					if (clientShouldClose[0]) {
						client.close();
					}
				}
				System.out.println("[SERVER] Sending: " + count);
				server.sendAllReliable(String.valueOf(count).getBytes(StandardCharsets.UTF_8));
				count++;
			}
			System.out.println("Done\n");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void improperDisconnectServer() {
		System.out.println("=== IMPROPER DC SERVER ===");
		final boolean[] serverShouldClose = {false};
		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[SERVER] Disconnected.");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				String s = new String(data, StandardCharsets.UTF_8);
				System.out.println("[SERVER] Received " + s);
				int result = Integer.parseInt(s);
				if (result > 6) {
					synchronized (serverShouldClose) {
						serverShouldClose[0] = true;
					}
				}
			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[CLIENT] Disconnected.");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {

			}
		};

		InetAddress address = InetAddress.getLoopbackAddress();

		try (Server server = new Server(2678, serverHandler);
			 Client client = new Client(address, 2678, clientHandler)) {
			server.start();
			client.connect();
			int count = 0;
			while (client.isOpen()) {
				Thread.sleep(500);
				synchronized (serverShouldClose) {
					if (serverShouldClose[0]) {
						server.close();
						break;
					}
				}
				System.out.println("[CLIENT] Sending: " + count);
				client.sendReliable(String.valueOf(count).getBytes(StandardCharsets.UTF_8));
				count++;
			}
			System.out.println("Done\n");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void closedConnectionTest() {
		System.out.println("=== CLOSED CONNECTION ===");
		InetAddress address = InetAddress.getLoopbackAddress();

		try (Client client = new Client(address, 2678, new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {

			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {

			}
		})) {
			client.connect();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void inactiveConnections() {
		System.out.println("=== INACTIVE CONNECTIONS ===");
		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {
				System.out.println("[SERVER] Connected " + address);
			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[SERVER] Disconnected " + address);
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				System.out.println("[SERVER] Received data.");
			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {
				System.out.println("[CLIENT] Connected");
			}

			@Override
			public void onDisconnect(SocketAddress address) {
				System.out.println("[CLIENT] Disconnected");
			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				System.out.println("[CLIENT] Received data.");
			}
		};

		InetAddress address = null;
		try {
			address = InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		try (Server server = new Server(2678, serverHandler);
			 Client client = new Client(address, 2678, clientHandler)) {
			server.start();
			client.connect();
			while (client.isOpen()) {
				Thread.sleep(5000);
				if (server.isOpen()) {
					System.out.println("5 seconds elapsed, closing server.");
					server.close();
				}
				//wait for the connection to die
			}
			System.out.println("Done\n");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void unreliable() {
		System.out.println("=== UNRELIABLE ===");
		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {

			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {

			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			int count = 0;
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {

			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				String s = new String(data, StandardCharsets.UTF_8);
				System.out.println("[CLIENT] " + count + ": Received " + s);
				count++;
				if (count > 20) {
					disconnect();
				}
			}
		};

		InetAddress address = null;
		try {
			address = InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		try (Server server = new Server(2678, serverHandler);
			 Client client = new Client(address, 2678, clientHandler)) {
			server.start();
			client.connect();
			int serverCount = 0;
			while (client.isOpen()) {
				Thread.sleep(100);
				server.sendAllRaw(String.valueOf(serverCount).getBytes(StandardCharsets.UTF_8));
				serverCount++;
			}
			System.out.println("Done\n");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void largeMessage() {
		System.out.println("=== LARGE MESSAGE ===");

		final byte[][] dataReceived = new byte[1][];
		final Object dataReceivedLock = new Object();

		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {

			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {

			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {

			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				System.out.println("[CLIENT] Received data of size: " + data.length);
				synchronized (dataReceivedLock) {
					dataReceived[0] = new byte[data.length];
					System.arraycopy(data, 0, dataReceived[0], 0, data.length);
				}
				disconnect();
			}
		};

		InetAddress address = InetAddress.getLoopbackAddress();

		try (Server server = new Server(2678, serverHandler);
			 Client client = new Client(address, 2678, clientHandler)) {
			server.start();
			client.connect();

			String fileContents = Files.readString(Paths.get("src/test/resources/the-full-bee-movie-script.txt"), StandardCharsets.UTF_8);
			byte[] data = fileContents.getBytes(StandardCharsets.UTF_8);
			System.out.println("File contents length: " + data.length);
			server.sendAllReliable(data);
			while (client.isOpen()) {
				Thread.sleep(100);
			}
			String received = new String(dataReceived[0], StandardCharsets.UTF_8);
			assertEquals(received, fileContents);
			System.out.println("Done\n");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void dataTooLarge() {
		System.out.println("=== VERY LARGE MESSAGE ===");

		ServerHandler serverHandler = new ServerHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {

			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {
				System.out.println("[SERVER] Received packet of length " + data.length);
				disconnect(address);
			}
		};

		ClientHandler clientHandler = new ClientHandler() {
			@Override
			public void onConnect(SocketAddress address) {

			}

			@Override
			public void onDisconnect(SocketAddress address) {

			}

			@Override
			public void onReceive(SocketAddress address, byte[] data) {

			}
		};

		InetAddress address = InetAddress.getLoopbackAddress();

		try (Server server = new Server(2678, serverHandler);
			 Client client = new Client(address, 2678, clientHandler)) {
			server.start();
			client.connect();

			byte[] largeData;
			try {
				largeData = Files.readAllBytes(Paths.get("src/test/resources/sticksinner.jpg"));
			} catch (IOException e) {
				System.out.println("Could not get large data.");
				return;
			}

			System.out.println("Large data length: " + largeData.length);

			assertThrows(IllegalArgumentException.class, () -> {
				client.sendReliable(largeData);
			});

		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

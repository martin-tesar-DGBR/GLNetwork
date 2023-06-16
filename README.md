# GLNetwork
A simple networking interface over UDP for sending and receiving messages reliably or unreliably.

# Purpose
The purpose of this project is to have a simple reference implementation of the ideas of a general reliable transport protocol for my personal use, filling in specific details that might otherwise be left out.
**Disclaimer:** This implementation is a work in progress, and is *not* meant to be fully performant and/or bug free.

# Sample code
```java
public static void main(String[] args) {
    final int receiveLimit = 40;
    ServerHandler serverHandler = new ServerHandler() {
        int receiveCount = 0;

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
            String s = new String(data, 0, data.length, StandardCharsets.UTF_8);
            System.out.println("[SERVER] Received: " + s);
            receiveCount++;
            if (receiveCount >= receiveLimit) {
                System.out.println("Called disconnect at " + receiveCount);
                disconnect(address);
            }
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
    try (Server server = new Server(2678, serverHandler); Client client = new Client(InetAddress.getLocalHost(), 2678, clientHandler)) {
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
```

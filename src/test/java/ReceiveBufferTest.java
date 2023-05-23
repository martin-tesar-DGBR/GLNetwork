import network.PacketUtils;
import network.ReceiveBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReceiveBufferTest {
	static final byte[] EMPTY = new byte[0];

	private byte[][] constructHeaders(int start, int length) {
		byte[][] headers = new byte[length][];

		for (int i = 0; i < length; i++) {
			headers[i] = PacketUtils.constructReliablePacket(EMPTY, start + i, 0);
		}
		return headers;
	}

	@Test
	public void test1() {
		int startSequenceNumber = 32672;
		ReceiveBuffer buffer = new ReceiveBuffer(6, startSequenceNumber);
		byte[][] packets = constructHeaders(startSequenceNumber, 17);
		buffer.add(packets[0]);
		buffer.add(packets[1]);
		buffer.add(packets[2]);
		buffer.add(packets[3]);
		assertEquals(buffer.size(), 4);
		assertFalse(buffer.isEmpty());
		assertFalse(buffer.isFull());
		byte[][] flush1 = buffer.flush();
		for (int i = 0; i < 4; i++) {
			assertEquals(flush1[i], packets[i]);
		}
		buffer.add(packets[4]);
		buffer.add(packets[5]);

		buffer.add(packets[7]);
		buffer.add(packets[8]);
		buffer.add(packets[9]);

		assertThrows(IllegalArgumentException.class, () -> {
			buffer.add(packets[3]);
		});
		assertThrows(IllegalArgumentException.class, () -> {
			buffer.add(packets[10]);
		});

		assertEquals(buffer.size(), 5);

		byte[][] flush2 = buffer.flush();
		for (int i = 4; i < 6; i++) {
			assertEquals(flush2[i - 4], packets[i]);
		}

		assertEquals(buffer.size(), 3);
		byte[][] flush3 = buffer.flush();
		assertEquals(flush3.length, 0);

		buffer.add(packets[6]);
		byte[][] flush4 = buffer.flush();
		for (int i = 6; i < 10; i++) {
			assertEquals(flush4[i - 6], packets[i]);
		}
		assertTrue(buffer.isEmpty());

		buffer.add(packets[10]);
		buffer.add(packets[11]);
		buffer.add(packets[12]);
		buffer.add(packets[13]);
		buffer.add(packets[14]);
		buffer.add(packets[15]);

		assertThrows(IllegalArgumentException.class, () -> {
			buffer.add(packets[16]);
		});

		byte[][] flush5 = buffer.flush();
		for (int i = 10; i < 16; i++) {
			assertEquals(flush5[i - 10], packets[i]);
		}
		assertTrue(buffer.isEmpty());
	}
}
import network.AcknowledgmentBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AcknowledgmentBufferTest {

	@Test
	void addSignal1() {
		AcknowledgmentBuffer buffer = new AcknowledgmentBuffer(6, 24);
		byte[][] data = {
			{(byte) 0xAA, 0, 0, 24, 0, 0},
			{(byte) 0xAA, 0, 0, 25, 0, 0},
			{(byte) 0xAA, 0, 0, 26, 0, 0},
			{(byte) 0xAA, 0, 0, 27, 0, 0},
			{(byte) 0xAA, 0, 0, 28, 0, 0},
			{(byte) 0xAA, 0, 0, 29, 0, 0},
			{(byte) 0xAA, 0, 0, 30, 0, 0},
			{(byte) 0xAA, 0, 0, 31, 0, 0},
			{(byte) 0xAA, 0, 0, 32, 0, 0},
			{(byte) 0xAA, 0, 0, 33, 0, 0},
			{(byte) 0xAA, 0, 0, 34, 0, 0},
			{(byte) 0xAA, 0, 0, 35, 0, 0},
			{(byte) 0xAA, 0, 0, 36, 0, 0},
			{(byte) 0xAA, 0, 0, 37, 0, 0},
			{(byte) 0xAA, 0, 0, 38, 0, 0},
		};
		buffer.add(data[0]);
		buffer.add(data[1]);
		buffer.add(data[2]);	// [24-, 25-, 26-, nnn, nnn, nnn]
		buffer.signalAck(26);		// [24-, 25-, 26a, nnn, nnn, nnn]
		buffer.signalAck(24);		// [nnn, 25-, 26a, nnn, nnn, nnn]
		assertEquals(buffer.size(), 2);
		buffer.signalAck(25);		// [nnn, nnn, nnn, nnn, nnn, nnn]
		assertEquals(buffer.size(), 0);

		buffer.add(data[3]);	// [nnn, nnn, nnn, 27-, nnn, nnn]
		buffer.add(data[4]);	// [nnn, nnn, nnn, 27-, 28-, nnn]
		buffer.add(data[5]);	// [nnn, nnn, nnn, 27-, 28-, 29-]
		assertEquals(buffer.size(), 3);
		buffer.signalAck(27);		// [nnn, nnn, nnn, nnn, 28-, 29-]
		assertEquals(buffer.size(), 2);

		buffer.add(data[6]);	// [30-, nnn, nnn, nnn, 28-, 29-]
		buffer.add(data[7]);	// [30-, 31-, nnn, nnn, 28-, 29-]
		assertEquals(buffer.size(), 4);

		buffer.signalAck(29);		// [30-, 31-, nnn, nnn, 28-, 29a]
		buffer.signalAck(30);		// [30a, 31-, nnn, nnn, 28-, 29a]
		buffer.signalAck(28);		// [nnn, 31-, nnn, nnn, nnn, nnn]
		assertEquals(buffer.size(), 1);

		buffer.add(data[8]);	// [nnn, 31-, 32-, nnn, nnn, nnn]
		buffer.add(data[9]);	// [nnn, 31-, 32-, 33-, nnn, nnn]
		buffer.add(data[10]);	// [nnn, 31-, 32-, 33-, 34-, nnn]
		buffer.add(data[11]);	// [nnn, 31-, 32-, 33-, 34-, 35-]
		assertFalse(buffer.isFull());
		buffer.add(data[12]);	// [36-, 31-, 32-, 33-, 34-, 35-]
		assertEquals(buffer.size(), 6);
		assertTrue(buffer.isFull());

		assertThrows(IllegalArgumentException.class, () -> {
			buffer.add(data[13]);
		});

		buffer.signalAck(36);		// [36a, 31-, 32-, 33-, 34-, 35-]
		buffer.signalAck(35);		// [36a, 31-, 32-, 33-, 34-, 35a]
		buffer.signalAck(34);		// [36a, 31-, 32a, 33-, 34a, 35a]
		buffer.signalAck(32);		// [36a, 31-, 32a, 33-, 34a, 35a]

		buffer.signalAck(31);		// [36a, nnn, nnn, 33-, 34a, 35a]
		assertEquals(buffer.size(), 4);
		assertFalse(buffer.isEmpty());
		buffer.signalAck(33);		// [nnn, nnn, nnn, nnn, nnn, nnn]
		assertEquals(buffer.size(), 0);
		assertTrue(buffer.isEmpty());
	}

	@Test
	void addSignal2() {
		AcknowledgmentBuffer buffer = new AcknowledgmentBuffer(6, 0xFFFA);
		byte[][] data = {
			{(byte) 0xAA, 0, (byte) 0xFF, (byte) 0xFA, 0, 0},
			{(byte) 0xAA, 0, (byte) 0xFF, (byte) 0xFB, 0, 0},
			{(byte) 0xAA, 0, (byte) 0xFF, (byte) 0xFC, 0, 0},
			{(byte) 0xAA, 0, (byte) 0xFF, (byte) 0xFD, 0, 0},
			{(byte) 0xAA, 0, (byte) 0xFF, (byte) 0xFE, 0, 0},
			{(byte) 0xAA, 0, (byte) 0xFF, (byte) 0xFF, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x00, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x01, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x02, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x03, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x04, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x05, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x06, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x07, 0, 0},
			{(byte) 0xAA, 0, (byte) 0x00, (byte) 0x08, 0, 0},
		};
		buffer.add(data[0]);
		buffer.add(data[1]);
		buffer.add(data[2]);	// [24-, 25-, 26-, nnn, nnn, nnn]
		buffer.signalAck(65532);		// [24-, 25-, 26a, nnn, nnn, nnn]
		buffer.signalAck(65530);		// [nnn, 25-, 26a, nnn, nnn, nnn]
		assertEquals(buffer.size(), 2);
		buffer.signalAck(65531);		// [nnn, nnn, nnn, nnn, nnn, nnn]
		assertEquals(buffer.size(), 0);

		buffer.add(data[3]);	// [nnn, nnn, nnn, 27-, nnn, nnn]
		buffer.add(data[4]);	// [nnn, nnn, nnn, 27-, 28-, nnn]
		buffer.add(data[5]);	// [nnn, nnn, nnn, 27-, 28-, 29-]
		assertEquals(buffer.size(), 3);
		buffer.signalAck(65533);		// [nnn, nnn, nnn, nnn, 28-, 29-]
		assertEquals(buffer.size(), 2);

		buffer.add(data[6]);
		buffer.add(data[7]);
		assertEquals(buffer.size(), 4);

		buffer.signalAck(65535);
		buffer.signalAck(0);
		buffer.signalAck(65534);
		assertEquals(buffer.size(), 1);

		buffer.add(data[8]);
		buffer.add(data[9]);
		buffer.add(data[10]);
		buffer.add(data[11]);
		assertFalse(buffer.isFull());
		buffer.add(data[12]);
		assertEquals(buffer.size(), 6);
		assertTrue(buffer.isFull());

		buffer.signalAck(6);
		buffer.signalAck(5);
		buffer.signalAck(4);
		buffer.signalAck(2);

		buffer.signalAck(1);
		assertEquals(buffer.size(), 4);
		assertFalse(buffer.isEmpty());
		buffer.signalAck(3);
		assertEquals(buffer.size(), 0);
		assertTrue(buffer.isEmpty());
	}

	@Test
	void checkAckedData() {
		AcknowledgmentBuffer buffer = new AcknowledgmentBuffer(6, 24);
		byte[][] data = {
			{(byte) 0xAA, 0, 0, 24, 0, 0},
			{(byte) 0xAA, 0, 0, 25, 0, 0},
			{(byte) 0xAA, 0, 0, 26, 0, 0},
			{(byte) 0xAA, 0, 0, 27, 0, 0},
			{(byte) 0xAA, 0, 0, 28, 0, 0},
			{(byte) 0xAA, 0, 0, 29, 0, 0},
			{(byte) 0xAA, 0, 0, 30, 0, 0},
			{(byte) 0xAA, 0, 0, 31, 0, 0},
			{(byte) 0xAA, 0, 0, 32, 0, 0},
			{(byte) 0xAA, 0, 0, 33, 0, 0},
			{(byte) 0xAA, 0, 0, 34, 0, 0},
			{(byte) 0xAA, 0, 0, 35, 0, 0},
			{(byte) 0xAA, 0, 0, 36, 0, 0},
			{(byte) 0xAA, 0, 0, 37, 0, 0},
			{(byte) 0xAA, 0, 0, 38, 0, 0},
		};

		buffer.add(data[0]);
		buffer.add(data[1]);
		buffer.add(data[2]);
		buffer.add(data[3]);										// [24-, 25-, 26-, 27-, nnn, nnn]
		assertFalse(buffer.isAcked(24));
		assertFalse(buffer.isAcked(25));
		assertFalse(buffer.isAcked(26));
		assertFalse(buffer.isAcked(27));
		assertEquals(buffer.findData(26), data[2]);
		assertEquals(buffer.findData(27), data[3]);
		assertNotEquals(buffer.findData(25), data[0]);
		assertThrows(IllegalArgumentException.class, () -> {
			byte[] illegalData = buffer.findData(28);
		});
		assertThrows(IllegalArgumentException.class, () -> {
			byte[] illegalData = buffer.findData(23);
		});

		buffer.signalAck(25);
		buffer.signalAck(27);							// [24-, 25a, 26-, 27a, nnn, nnn]
		assertFalse(buffer.isAcked(24));
		assertTrue(buffer.isAcked(25));
		assertFalse(buffer.isAcked(26));
		assertTrue(buffer.isAcked(27));

		assertTrue(buffer.isAcked(23));
		assertTrue(buffer.isAcked(28)); //technically an invalid comparison

		buffer.signalAck(24);							// [nnn, nnn, 26-, 27-, nnn, nnn]
		assertThrows(IllegalArgumentException.class, () -> {
			byte[] illegalData = buffer.findData(25);
		});
		assertTrue(buffer.isAcked(24));
		assertTrue(buffer.isAcked(25));
		buffer.signalAck(26);

		assertThrows(IllegalArgumentException.class, () -> {
			byte[] illegalData = buffer.findData(26);
		});
		assertTrue(buffer.isEmpty());

		buffer.add(data[4]);
		buffer.add(data[5]);
		buffer.add(data[6]);
		buffer.add(data[7]);
		assertFalse(buffer.isAcked(28));
		assertFalse(buffer.isAcked(29));
		assertFalse(buffer.isAcked(30));
		assertFalse(buffer.isAcked(31));

		assertThrows(IllegalArgumentException.class, () -> {
			byte[] illegalData = buffer.findData(32);
		});
		assertThrows(IllegalArgumentException.class, () -> {
			byte[] illegalData = buffer.findData(27);
		});

		buffer.signalAck(30);
		buffer.signalAck(29);
		assertFalse(buffer.isAcked(28));
		assertTrue(buffer.isAcked(29));
		assertTrue(buffer.isAcked(30));
		assertFalse(buffer.isAcked(31));
	}
}
package network;

public class PacketUtils {
	private PacketUtils() {}

	public static final int MAX_PACKETS_IN_FLIGHT = 32;
	public static final int MAX_PACKET_SIZE = 1024;

	static final int HEADER_SIZE = 6;
	static final byte VERSION_ID = (byte) 0xAA;

	static final byte SYN_MASK = (byte) (0x80 & 0xFF);
	static final byte ACK_MASK = (byte) (0x40 & 0xFF);
	static final byte RELIABLE_MASK = (byte) (0x20 & 0xFF);
	static final byte FIN_MASK = (byte) (0x10 & 0xFF);
	static final byte HEARTBEAT_MASK = (byte) (0x08 & 0xFF);

	public static byte[] constructUnreliablePacket(byte[] data) {
		byte[] ret = new byte[HEADER_SIZE + data.length];
		System.arraycopy(data, 0, ret, HEADER_SIZE, data.length);
		ret[0] = VERSION_ID;
		ret[1] = 0;
		ret[2] = 0;
		ret[3] = 0;
		ret[4] = 0;
		ret[5] = 0;
		return ret;
	}

	public static byte[] constructReliablePacket(byte[] data, int seqNum, int ackNum) {
		byte[] ret = new byte[HEADER_SIZE + data.length];
		System.arraycopy(data, 0, ret, HEADER_SIZE, data.length);
		ret[0] = VERSION_ID;
		ret[1] = RELIABLE_MASK;
		ret[2] = (byte) ((seqNum >> 8) & 0xFF);
		ret[3] = (byte) ((seqNum >> 0) & 0xFF);
		ret[4] = (byte) ((ackNum >> 8) & 0xFF);
		ret[5] = (byte) ((ackNum >> 0) & 0xFF);
		return ret;
	}

	public static byte[] constructSYNPacket(int seqNum) {
		byte[] ret = new byte[HEADER_SIZE];
		ret[0] = VERSION_ID;
		ret[1] = SYN_MASK | RELIABLE_MASK;
		ret[2] = (byte) ((seqNum >> 8) & 0xFF);
		ret[3] = (byte) ((seqNum >> 0) & 0xFF);
		ret[4] = 0;
		ret[5] = 0;
		return ret;
	}

	public static byte[] constructSYNACKPacket(int seqNum, int ackNum) {
		byte[] ret = new byte[HEADER_SIZE];
		ret[0] = VERSION_ID;
		ret[1] = SYN_MASK | ACK_MASK | RELIABLE_MASK;
		ret[2] = (byte) ((seqNum >> 8) & 0xFF);
		ret[3] = (byte) ((seqNum >> 0) & 0xFF);
		ret[4] = (byte) ((ackNum >> 8) & 0xFF);
		ret[5] = (byte) ((ackNum >> 0) & 0xFF);
		return ret;
	}

	public static byte[] constructACKPacket(int seqNum, int ackNum) {
		byte[] ret = new byte[HEADER_SIZE];
		ret[0] = VERSION_ID;
		ret[1] = ACK_MASK;
		ret[2] = (byte) ((seqNum >> 8) & 0xFF);
		ret[3] = (byte) ((seqNum >> 0) & 0xFF);
		ret[4] = (byte) ((ackNum >> 8) & 0xFF);
		ret[5] = (byte) ((ackNum >> 0) & 0xFF);
		return ret;
	}

	public static byte[] constructFINPacket(int seqNum, int ackNum) {
		byte[] ret = new byte[HEADER_SIZE];
		ret[0] = VERSION_ID;
		ret[1] = RELIABLE_MASK | FIN_MASK;
		ret[2] = (byte) ((seqNum >> 8) & 0xFF);
		ret[3] = (byte) ((seqNum >> 0) & 0xFF);
		ret[4] = (byte) ((ackNum >> 8) & 0xFF);
		ret[5] = (byte) ((ackNum >> 0) & 0xFF);
		return ret;
	}

	public static byte[] constructFINACKPacket(int seqNum, int ackNum) {
		byte[] ret = new byte[HEADER_SIZE];
		ret[0] = VERSION_ID;
		ret[1] = ACK_MASK | RELIABLE_MASK | FIN_MASK;
		ret[2] = (byte) ((seqNum >> 8) & 0xFF);
		ret[3] = (byte) ((seqNum >> 0) & 0xFF);
		ret[4] = (byte) ((ackNum >> 8) & 0xFF);
		ret[5] = (byte) ((ackNum >> 0) & 0xFF);
		return ret;
	}

	public static byte[] constructHeartbeatPacket(int seqNum, int ackNum) {
		byte[] ret = new byte[HEADER_SIZE];
		ret[0] = VERSION_ID;
		ret[1] = RELIABLE_MASK | HEARTBEAT_MASK;
		ret[2] = (byte) ((seqNum >> 8) & 0xFF);
		ret[3] = (byte) ((seqNum >> 0) & 0xFF);
		ret[4] = (byte) ((ackNum >> 8) & 0xFF);
		ret[5] = (byte) ((ackNum >> 0) & 0xFF);
		return ret;
	}

	public static boolean isValidPacket(byte[] data) {
		//TODO: add hashing check
		return data.length >= 6 && data[0] == VERSION_ID;
	}

	//the following isFLAG methods are mostly for semantic purposes i.e. what should we be checking for
	public static boolean isSYN(byte[] header) {
		if (!isValidPacket(header)) {
			throw new IllegalArgumentException("Invalid header given.");
		}
		return compareFlag(header[1], SYN_MASK | ACK_MASK, SYN_MASK);
	}

	public static boolean isSYNACK(byte[] header) {
		if (!isValidPacket(header)) {
			throw new IllegalArgumentException("Invalid header given.");
		}
		return compareFlag(header[1], SYN_MASK | ACK_MASK);
	}

	public static boolean isACK(byte[] header) {
		if (!isValidPacket(header)) {
			throw new IllegalArgumentException("Invalid header given.");
		}
		return compareFlag(header[1], SYN_MASK | ACK_MASK, ACK_MASK);
	}

	public static boolean isFIN(byte[] header) {
		if (!isValidPacket(header)) {
			throw new IllegalArgumentException("Invalid header given.");
		}
		return compareFlag(header[1], RELIABLE_MASK | FIN_MASK);
	}

	public static boolean isFINACK(byte[] header) {
		if (!isValidPacket(header)) {
			throw new IllegalArgumentException("Invalid header given.");
		}
		return compareFlag(header[1], ACK_MASK | FIN_MASK);
	}

	public static boolean isReliable(byte[] header) {
		if (!isValidPacket(header)) {
			throw new IllegalArgumentException("Invalid header given.");
		}
		return compareFlag(header[1], ACK_MASK | RELIABLE_MASK, RELIABLE_MASK);
	}

	public static byte getFlags(byte[] header) {
		if (!isValidPacket(header)) {
			throw new IllegalArgumentException("Invalid header given.");
		}
		return header[1];
	}

	public static int getSeqNum(byte[] data) {
		int seqNum = (((data[2] & 0xFF) << 8) | (data[3] & 0xFF)) & 0xFFFF;
		return seqNum;
	}

	public static int getAckNum(byte[] data) {
		int ackNum = (((data[4] & 0xFF) << 8) | (data[5] & 0xFF)) & 0xFFFF;
		return ackNum;
	}

	public static boolean compareFlag(byte flags, int mask, int result) {
		return (flags & mask) == result;
	}

	public static boolean compareFlag(byte flags, int mask) {
		return (flags & mask) == mask;
	}

	public static boolean sequenceGreaterThan(int s1, int s2) {
		s1 &= 0xFFFF;
		s2 &= 0xFFFF;
		return (s1 > s2 && s1 - s2 <= 0x8000) || (s1 < s2 && s2 - s1 > 0x8000);
	}

	public static void printData(byte[] data) {
		for (int i = 0; i < data.length; i++) {
			System.out.printf("%x ", data[i]);
			if (i % 16 == 0 && i != 0) {
				System.out.println();
			}
			else if (i % 16 == 7) {
				System.out.print("| ");
			}
		}
		System.out.println();
	}
}

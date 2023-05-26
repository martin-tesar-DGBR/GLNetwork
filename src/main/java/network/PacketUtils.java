package network;

public class PacketUtils {
	private PacketUtils() {}

	/*
	 * PACKET FORMAT:
	 * each row is 16 bits, each column in a row separated by + is 8 bits
	 * +-------------------------------+-------------------------------+
	 * +                               + S | A | R | C | H | F |   |   +
	 * +          PROTOCOL ID          + Y | C | L | N | B | I |   |   +
	 * +                               + N | K | B | K | T | N |   |   +
	 * +-------------------------------+-------------------------------+
	 * +                                                               +
	 * +                       SEQUENCE NUMBER                         +
	 * +                                                               +
	 * +-------------------------------+-------------------------------+
	 * +                                                               +
	 * +                          EXTRA DATA                           +
	 * +                                                               +
	 * +-------------------------------+-------------------------------+
	 *
	 * FLAG INFO:
	 * 		SYN:
	 * 			Used for initiating a connection between two endpoints. The endpoint that wishes to connect sends a
	 * 			packet with only the SYN and RELIABLE flags set, and the sequence number field should contain the
	 * 			sequence number this endpoint wishes to start at.
	 * 			If both SYN and ACK bits are set, this indicates that the receiving endpoint has received a SYN, and is
	 * 			responding to it with its own sequence number. The 'Extra Data' field should contain the sequence number
	 * 			of the connection it is replying to, and the sequence number field should have the receiver's own
	 * 			sequence number.
	 * 		ACK:
	 * 			Used as a response to a given message. The 'Extra Data' field should contain the sequence number the
	 * 			acknowledgment is meant for. In general, reliable exchanges work as follows:
	 * 				- Sender sends message with sequence number x
	 * 				- Receiver sends back an ACK message with ack number the same x
	 * 				- Sender's next message has sequence number x + 1
	 * 		RLB:
	 * 			Marks the message as reliable, and requires the receiver to respond with an ACK.
	 * 		CNK:
	 * 			Marks the message as chunked, which means the receiver should buffer these messages, and deliver the
	 * 			full data once all chunks arrive.
	 * 			The 'Extra Data' field has the number of expected chunks in the first octet, interpreted as an
	 * 			unsigned integer plus one (e.g. 0 => 1 chunk, 1 => 2 chunks, ... , 255 => 256 chunks), and which
	 * 			chunk the message corresponds to in the second octet.
	 * 		HBT:
	 * 			Marks the message as a heartbeat, which means that the packet contains no data but the sender expects
	 * 			an acknowledgment anyway. This is meant to make sure the connection is still alive on both ends even if
	 * 			both sides aren't sending any user messages.
	 * 		FIN:
	 * 			Marks the message as a disconnect message. The disconnect protocol is as follows:
	 * 				- Sender sends a reliable FIN message. No further messages should be accepted after this one.
	 * 				- Receiver sends a FIN-ACK message, with both FIN and ACK flags set. Receiver closes their endpoint.
	 * 				- Sender receives the FIN-ACK, and closes their endpoint.
	 * 			If at any point during this process the messages don't arrive and/or the message times out, both sides
	 * 			will end up closing their connection anyway. It is up to the user to decide when is a good time to close
	 * 			the connection, as the receiver may be in the middle of transmitting data when it receives a FIN.
	 */

	static final int HEADER_SIZE = 6;
	static final byte VERSION_ID = (byte) 0xAA;

	static final byte SYN_MASK = (byte) (0x80 & 0xFF);
	static final byte ACK_MASK = (byte) (0x40 & 0xFF);
	static final byte RELIABLE_MASK = (byte) (0x20 & 0xFF);
	static final byte CHUNKED_MASK = (byte) (0x10 & 0xFF);
	static final byte HEARTBEAT_MASK = (byte) (0x08 & 0xFF);
	static final byte FIN_MASK = (byte) (0x04 & 0xFF);

	public static final int MAX_PACKETS_IN_FLIGHT = 32;
	public static final int MAX_PACKET_SIZE = 1024;
	public static final int MAX_NUM_CHUNKS = 256;
	public static final int MAX_DATA_PER_CHUNK = MAX_PACKET_SIZE - HEADER_SIZE;
	public static final int MAX_PAYLOAD_SIZE = MAX_NUM_CHUNKS * (MAX_PACKET_SIZE - HEADER_SIZE);

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

	public static byte[][] constructReliableChunkedPackets(byte[] data, int seqNum) {
		if (data.length > MAX_PAYLOAD_SIZE) {
			throw new IllegalArgumentException("Data length " + data.length + " exceeds maximum payload size " + MAX_PAYLOAD_SIZE);
		}
		int numChunks = data.length / MAX_DATA_PER_CHUNK + (data.length % MAX_DATA_PER_CHUNK == 0 ? 0 : 1); //ceiling division
		byte[][] chunkedData = new byte[numChunks][];
		for (int i = 0; i < numChunks; i++) {
			int chunkedDataLength = i == chunkedData.length - 1 ? (data.length % MAX_DATA_PER_CHUNK) + HEADER_SIZE : MAX_PACKET_SIZE;
			chunkedData[i] = new byte[chunkedDataLength];
			chunkedData[i][0] = VERSION_ID;
			chunkedData[i][1] = RELIABLE_MASK | CHUNKED_MASK;
			int chunkSeqNum = (seqNum + i) & 0xFFFF;
			chunkedData[i][2] = (byte) ((chunkSeqNum >> 8) & 0xFF);
			chunkedData[i][3] = (byte) ((chunkSeqNum >> 0) & 0xFF);
			chunkedData[i][4] = (byte) ((numChunks - 1) & 0xFF);
			chunkedData[i][5] = (byte) (i & 0xFF);
			System.arraycopy(data, MAX_DATA_PER_CHUNK * i, chunkedData[i], HEADER_SIZE, chunkedDataLength - HEADER_SIZE);
		}
		return chunkedData;
	}

	public static byte[] assembleDataFromChunks(byte[][] chunkedData, int numChunks) {
		byte[] data = new byte[((numChunks - 1) * MAX_DATA_PER_CHUNK) + (chunkedData[numChunks - 1].length - HEADER_SIZE)];
		for (int i = 0, acc = 0; i < numChunks; i++) {
			int chunkDataLength = chunkedData[i].length - HEADER_SIZE;
			System.arraycopy(chunkedData[i], HEADER_SIZE, data, acc, chunkDataLength);
			acc += chunkDataLength;
		}
		return data;
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

	public static int getNumChunks(byte[] data) {
		return (data[4] & 0xFF) + 1;
	}

	public static int getChunkIndex(byte[] data) {
		return (data[5] & 0xFF);
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

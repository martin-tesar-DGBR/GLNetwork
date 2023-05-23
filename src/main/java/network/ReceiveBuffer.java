package network;

public class ReceiveBuffer {
	private byte[][] buffer;
	private boolean[] occupied;
	private int capacity;
	private int size = 0;
	private int tailIndex = 0;
	private int smallestSequenceNumber;

	public ReceiveBuffer(int capacity, int startSequenceNumber) {
		this.capacity = capacity;
		this.buffer = new byte[capacity][];
		this.occupied = new boolean[capacity];
		this.smallestSequenceNumber = startSequenceNumber;
	}

	public synchronized int size() {
		return size;
	}

	public synchronized boolean isEmpty() {
		return size == 0;
	}

	public synchronized boolean isFull() {
		return size >= capacity;
	}

	public synchronized void add(byte[] data) {
		int sequenceNumber = PacketUtils.getSeqNum(data);
		int offset = getOffset(sequenceNumber);
		if (offset >= capacity || PacketUtils.sequenceGreaterThan(smallestSequenceNumber, sequenceNumber)) {
			throw new IllegalArgumentException(
				"Provided sequence number " + sequenceNumber + " is out of range for current " +
					smallestSequenceNumber + " and capacity " + capacity);
		}
		int insertIndex = (tailIndex + offset) % capacity;
		if (occupied[insertIndex]) {
			throw new IllegalArgumentException("Cannot add data that already exists.");
		}
		buffer[insertIndex] = data;
		occupied[insertIndex] = true;
		size++;
	}

	public synchronized byte[][] flush() {
		int flushSize = 0;
		for (int i = tailIndex; occupied[i] && flushSize < size; i = (i + 1) % capacity) {
			flushSize++;
		}
		byte[][] ret = new byte[flushSize][];
		for (int index = 0; index < flushSize; tailIndex = (tailIndex + 1) % capacity, index++) {
			ret[index] = buffer[tailIndex];
			occupied[tailIndex] = false;
		}
		smallestSequenceNumber = (smallestSequenceNumber + flushSize) & 0xFFFF;
		size -= flushSize;
		return ret;
	}

	public boolean isOccupied(int sequenceNumber) {
		int offset = getOffset(sequenceNumber);
		if (offset >= capacity || PacketUtils.sequenceGreaterThan(smallestSequenceNumber, sequenceNumber)) {
			return true;
		}
		int index = (tailIndex + offset) % capacity;
		return occupied[index];
	}

	public boolean inRange(int sequenceNumber) {
		int offset = getOffset(sequenceNumber);
		return offset < capacity && !PacketUtils.sequenceGreaterThan(smallestSequenceNumber, sequenceNumber);
	}

	public int getExpectedSequenceNumber() {
		return this.smallestSequenceNumber;
	}

	public int getMaxExpectedSequenceNumber() {
		return (this.smallestSequenceNumber + capacity - 1) & 0xFFFF;
	}

	private int getOffset(int seqNum) {
		return (seqNum - smallestSequenceNumber) & 0xFFFF;
	}
}

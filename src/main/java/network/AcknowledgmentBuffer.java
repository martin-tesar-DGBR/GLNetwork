package network;

import java.util.Arrays;

public class AcknowledgmentBuffer {
	private byte[][] buffer;
	private int[] timesAccessed;
	private boolean[] isAcked;
	private int capacity;
	private int size = 0;
	private int tailIndex = 0;

	private int smallestSequenceNumber;

	public AcknowledgmentBuffer(int capacity, int startingSequenceNumber) {
		this.capacity = capacity;
		buffer = new byte[capacity][];
		timesAccessed = new int[capacity];
		Arrays.fill(timesAccessed, 0);
		isAcked = new boolean[capacity];
		Arrays.fill(isAcked, false);
		this.smallestSequenceNumber = startingSequenceNumber;
	}

	public synchronized int size() {
		return size;
	}

	public synchronized boolean isEmpty() {
		return size == 0;
	}

	public synchronized boolean isFull() {
		return size == capacity;
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
		buffer[insertIndex] = data;
		size++;
	}

	public synchronized void signalAck(int sequenceNumber) {
		int offset = getOffset(sequenceNumber);
		if (offset >= capacity || PacketUtils.sequenceGreaterThan(smallestSequenceNumber, sequenceNumber)) {
			throw new IllegalArgumentException(
				"Provided sequence number " + sequenceNumber + " is out of range for current " +
				smallestSequenceNumber + " and capacity " + capacity);
		}
		int index = (tailIndex + offset) % capacity;
		isAcked[index] = true;

		if (index == tailIndex) {
			for (; isAcked[tailIndex]; tailIndex = (tailIndex + 1) % capacity) {
				isAcked[tailIndex] = false;
				timesAccessed[tailIndex] = 0;
				size--;
				smallestSequenceNumber = (smallestSequenceNumber + 1) & 0xFFFF;
			}
		}
	}

	public synchronized byte[] findData(int sequenceNumber) {
		if (size == 0) {
			throw new IllegalArgumentException("Cannot find data in buffer of size 0.");
		}
		int offset = getOffset(sequenceNumber);
		if (offset >= capacity || PacketUtils.sequenceGreaterThan(smallestSequenceNumber, sequenceNumber)) {
			throw new IllegalArgumentException(
				"Provided sequence number " + sequenceNumber + " is out of range for current " +
				smallestSequenceNumber + " and capacity " + capacity);
		}
		int index = (tailIndex + offset) % capacity;
		if (index >= tailIndex + size || (index < tailIndex && index >= (tailIndex + size) % capacity)) {
			throw new IllegalArgumentException("Attempted to find expired data.");
		}
		timesAccessed[index]++;
		return buffer[index];
	}

	public synchronized int getTimesAccessed(int sequenceNumber) {
		if (size == 0) {
			throw new IllegalArgumentException("Cannot find data in buffer of size 0.");
		}

		int offset = getOffset(sequenceNumber);
		if (offset >= capacity || PacketUtils.sequenceGreaterThan(smallestSequenceNumber, sequenceNumber)) {
			throw new IllegalArgumentException(
				"Provided sequence number " + sequenceNumber + " is out of range for current " +
					smallestSequenceNumber + " and capacity " + capacity);
		}

		int index = (tailIndex + offset) % capacity;
		if (index >= tailIndex + size || (index < tailIndex && index >= (tailIndex + size) % capacity)) {
			throw new IllegalArgumentException("Attempted to find expired data.");
		}

		return timesAccessed[index];
	}

	public synchronized boolean isAcked(int sequenceNumber) {
		if (size == 0) {
			return true;
		}

		int offset = getOffset(sequenceNumber);
		if (offset >= capacity || PacketUtils.sequenceGreaterThan(smallestSequenceNumber, sequenceNumber)) {
			return true;
		}

		int index = (tailIndex + offset) % capacity;
		if (index >= tailIndex + size || (index < tailIndex && index >= (tailIndex + size) % capacity)) {
			return true;
		}

		return isAcked[index];
	}

	public synchronized int getExpectedSequenceNumber() {
		return smallestSequenceNumber;
	}

	private int getOffset(int seqNum) {
		return (seqNum - smallestSequenceNumber) & 0xFFFF;
	}
}

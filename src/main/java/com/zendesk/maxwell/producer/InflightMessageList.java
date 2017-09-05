package com.zendesk.maxwell.producer;
/* respresents a list of inflight messages -- stuff being sent over the
   network, that may complete in any order.  Allows for only bumping
   the binlog position upon completion of the oldest outstanding item.

   Assumes .addInflight(position) will be call monotonically.
   */

import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.replication.Position;

import java.util.Iterator;
import java.util.LinkedHashMap;

public class InflightMessageList {

	class InflightMessage {
		public final Position position;
		public boolean isComplete;
		public final long sendTimeMS;

		InflightMessage(Position p) {
			this.position = p;
			this.isComplete = false;
			this.sendTimeMS = System.currentTimeMillis();
		}

		long staleness() {
			return System.currentTimeMillis() - sendTimeMS;
		}
	}

	private static final long INIT_CAPACITY = 1000;
	private static final double COMPLETE_PERCENTAGE_THRESHOLD = 0.9;

	private final LinkedHashMap<Position, InflightMessage> linkedMap;
	private final MaxwellContext context;
	private final long capacity;
	private final long inflightRequestTimeoutMS;
	private final double completePercentageThreshold;
	private volatile boolean isFull;
	private InflightMessage head;

	public InflightMessageList(MaxwellContext context) {
		this(context, INIT_CAPACITY, COMPLETE_PERCENTAGE_THRESHOLD);
	}

	public InflightMessageList(MaxwellContext context, long capacity, double completePercentageThreshold) {
		this.context = context;
		this.inflightRequestTimeoutMS = context.getConfig().inflightRequestTimeout;
		this.completePercentageThreshold = completePercentageThreshold;
		this.linkedMap = new LinkedHashMap<>();
		this.capacity = capacity;
	}

	public long addMessage(Position p) throws InterruptedException {
		synchronized (this.linkedMap) {
			while (isFull) {
				this.linkedMap.wait();
			}

			InflightMessage m = new InflightMessage(p);
			this.linkedMap.put(p, m);

			int size = linkedMap.size();
			if (size >= capacity) {
				isFull = true;
			} else if (size == 1) {
				head = m;
			}
			return m.sendTimeMS;
		}
	}

	/* returns the position that stuff is complete up to, or null if there were no changes */
	public InflightMessage completeMessage(Position p) {
		synchronized (this.linkedMap) {
			InflightMessage m = this.linkedMap.get(p);
			assert(m != null);

			m.isComplete = true;

			InflightMessage completeUntil = null;
			if (p.equals(head.position)) {
				Iterator<InflightMessage> iterator = this.linkedMap.values().iterator();

				while ( iterator.hasNext() ) {
					InflightMessage msg = iterator.next();
					if ( !msg.isComplete ) {
						head = msg;
						break;
					}

					completeUntil = msg;
					iterator.remove();
				}

				if (isFull && linkedMap.size() < capacity) {
					isFull = false;
					this.linkedMap.notify();
				}
			}

			// If the head is stuck for the length of time (configurable) and majority of the messages have completed,
			// we assume the head will unlikely get acknowledged, hence terminate Maxwell.
			// This gatekeeper is the last resort since if anything goes wrong,
			// producer should have raised exceptions earlier than this point when all below conditions are met.
			if (inflightRequestTimeoutMS > 0 && isFull && head.staleness() > inflightRequestTimeoutMS
					&& completePercentage() >= completePercentageThreshold) {
				context.terminate(new IllegalStateException(
						"Did not receive acknowledgement for the head of the inflight message list for " + inflightRequestTimeoutMS + " ms"));
			}

			return completeUntil;
		}
	}

	public int size() {
		return linkedMap.size();
	}

	private double completePercentage() {
		int completed = 0;
		for (InflightMessage m : linkedMap.values()) {
			if (m.isComplete)
				completed ++;
		}
		return ((double) completed) / ((double) linkedMap.size());
	}
}

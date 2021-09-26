package redis.clients.jedis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.util.SafeEncoder;

public class JedisClusterInfoCache {

  private final Map<String, Pool<JedisConnection>> nodes = new HashMap<>();
  private final Map<Integer, Pool<JedisConnection>> slots = new HashMap<>();
  private final Map<Integer, HostAndPort> slotNodes = new HashMap<>();

  private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
  private final Lock r = rwl.readLock();
  private final Lock w = rwl.writeLock();
  private final Lock rediscoverLock = new ReentrantLock();

  private final GenericObjectPoolConfig<JedisConnection> poolConfig;
  private final JedisClientConfig clientConfig;

  private static final int MASTER_NODE_INDEX = 2;

  public JedisClusterInfoCache(final JedisClientConfig clientConfig) {
    this(clientConfig, new GenericObjectPoolConfig<JedisConnection>());
  }

  public JedisClusterInfoCache(final JedisClientConfig clientConfig,
      final GenericObjectPoolConfig<JedisConnection> poolConfig) {
    this.poolConfig = poolConfig;
    this.clientConfig = clientConfig;
  }

  public void discoverClusterNodesAndSlots(JedisConnection jedis) {
    w.lock();

    try {
      reset();
      List<Object> slotsInfo = executeClusterSlots(jedis);

      for (Object slotInfoObj : slotsInfo) {
        List<Object> slotInfo = (List<Object>) slotInfoObj;

        if (slotInfo.size() <= MASTER_NODE_INDEX) {
          continue;
        }

        List<Integer> slotNums = getAssignedSlotArray(slotInfo);

        // hostInfos
        int size = slotInfo.size();
        for (int i = MASTER_NODE_INDEX; i < size; i++) {
          List<Object> hostInfos = (List<Object>) slotInfo.get(i);
          if (hostInfos.isEmpty()) {
            continue;
          }

          HostAndPort targetNode = generateHostAndPort(hostInfos);
          setupNodeIfNotExist(targetNode);
          if (i == MASTER_NODE_INDEX) {
            assignSlotsToNode(slotNums, targetNode);
          }
        }
      }
    } finally {
      w.unlock();
    }
  }

  public void renewClusterSlots(JedisConnection jedis) {
    // If rediscovering is already in process - no need to start one more same rediscovering, just return
    if (rediscoverLock.tryLock()) {
      try {
        if (jedis != null) {
          try {
            discoverClusterSlots(jedis);
            return;
          } catch (JedisException e) {
            // try nodes from all pools
          }
        }

        for (Pool<JedisConnection> jp : getShuffledNodesPool()) {
          JedisConnection j = null;
          try {
            j = jp.getResource();
            discoverClusterSlots(j);
            return;
          } catch (JedisConnectionException e) {
            // try next nodes
          } finally {
            if (j != null) {
              j.close();
            }
          }
        }
      } finally {
        rediscoverLock.unlock();
      }
    }
  }

  private void discoverClusterSlots(JedisConnection jedis) {
    List<Object> slotsInfo = executeClusterSlots(jedis);
    w.lock();
    try {
      this.slots.clear();
      this.slotNodes.clear();

      for (Object slotInfoObj : slotsInfo) {
        List<Object> slotInfo = (List<Object>) slotInfoObj;

        if (slotInfo.size() <= MASTER_NODE_INDEX) {
          continue;
        }

        List<Integer> slotNums = getAssignedSlotArray(slotInfo);

        // hostInfos
        List<Object> hostInfos = (List<Object>) slotInfo.get(MASTER_NODE_INDEX);
        if (hostInfos.isEmpty()) {
          continue;
        }

        // at this time, we just use master, discard slave information
        HostAndPort targetNode = generateHostAndPort(hostInfos);
        assignSlotsToNode(slotNums, targetNode);
      }
    } finally {
      w.unlock();
    }
  }

  private HostAndPort generateHostAndPort(List<Object> hostInfos) {
    String host = SafeEncoder.encode((byte[]) hostInfos.get(0));
    int port = ((Long) hostInfos.get(1)).intValue();
    return new HostAndPort(host, port);
  }

  public Pool<JedisConnection> setupNodeIfNotExist(final HostAndPort node) {
    w.lock();
    try {
      String nodeKey = getNodeKey(node);
      Pool<JedisConnection> existingPool = nodes.get(nodeKey);
      if (existingPool != null) return existingPool;

      Pool<JedisConnection> nodePool = new JedisConnectionPool(poolConfig, node, clientConfig);
      nodes.put(nodeKey, nodePool);
      return nodePool;
    } finally {
      w.unlock();
    }
  }

  public void assignSlotToNode(int slot, HostAndPort targetNode) {
    w.lock();
    try {
      Pool<JedisConnection> targetPool = setupNodeIfNotExist(targetNode);
      slots.put(slot, targetPool);
      slotNodes.put(slot, targetNode);
    } finally {
      w.unlock();
    }
  }

  public void assignSlotsToNode(List<Integer> targetSlots, HostAndPort targetNode) {
    w.lock();
    try {
      Pool<JedisConnection> targetPool = setupNodeIfNotExist(targetNode);
      for (Integer slot : targetSlots) {
        slots.put(slot, targetPool);
        slotNodes.put(slot, targetNode);
      }
    } finally {
      w.unlock();
    }
  }

  public Pool<JedisConnection> getNode(String nodeKey) {
    r.lock();
    try {
      return nodes.get(nodeKey);
    } finally {
      r.unlock();
    }
  }

  public Pool<JedisConnection> getNode(HostAndPort node) {
    return getNode(getNodeKey(node));
  }

  public Pool<JedisConnection> getSlotPool(int slot) {
    r.lock();
    try {
      return slots.get(slot);
    } finally {
      r.unlock();
    }
  }

  public HostAndPort getSlotNode(int slot) {
    r.lock();
    try {
      return slotNodes.get(slot);
    } finally {
      r.unlock();
    }
  }

  public Map<String, Pool<JedisConnection>> getNodes() {
    r.lock();
    try {
      return new HashMap<>(nodes);
    } finally {
      r.unlock();
    }
  }

  public List<Pool<JedisConnection>> getShuffledNodesPool() {
    r.lock();
    try {
      List<Pool<JedisConnection>> pools = new ArrayList<>(nodes.values());
      Collections.shuffle(pools);
      return pools;
    } finally {
      r.unlock();
    }
  }

  /**
   * Clear discovered nodes collections and gently release allocated resources
   */
  public void reset() {
    w.lock();
    try {
      for (Pool<JedisConnection> pool : nodes.values()) {
        try {
          if (pool != null) {
            pool.destroy();
          }
        } catch (Exception e) {
          // pass
        }
      }
      nodes.clear();
      slots.clear();
      slotNodes.clear();
    } finally {
      w.unlock();
    }
  }

  public static String getNodeKey(HostAndPort hnp) {
    //return hnp.getHost() + ":" + hnp.getPort();
    return hnp.toString();
  }

  private List<Object> executeClusterSlots(JedisConnection jedis) {
    jedis.sendCommand(Protocol.Command.CLUSTER, "SLOTS");
    return jedis.getObjectMultiBulkReply();
  }

  private List<Integer> getAssignedSlotArray(List<Object> slotInfo) {
    List<Integer> slotNums = new ArrayList<>();
    for (int slot = ((Long) slotInfo.get(0)).intValue(); slot <= ((Long) slotInfo.get(1))
        .intValue(); slot++) {
      slotNums.add(slot);
    }
    return slotNums;
  }
}

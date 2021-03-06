package zx.soft.sent.solr.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;
import zx.soft.sent.dao.domain.platform.RecordInfo;
import zx.soft.utils.config.ConfigUtil;
import zx.soft.utils.json.JsonUtils;
import zx.soft.utils.log.LogbackUtil;

public class RedisMQ {

	private static Logger logger = LoggerFactory.getLogger(RedisMQ.class);

	private static JedisPool pool;

	private static final String CACHE_SENTIMENT_KEY = "cache-records";

	private static final ObjectMapper OBJECT_MAPPER = JsonUtils.getObjectMapper();

	public RedisMQ() {
		JedisPoolConfig poolConfig = new JedisPoolConfig();
		poolConfig.setMaxIdle(128);
		poolConfig.setMinIdle(64);
		poolConfig.setMaxWaitMillis(10_000);
		poolConfig.setMaxTotal(256);
		poolConfig.setTestOnBorrow(true);
		poolConfig.setTimeBetweenEvictionRunsMillis(30000);
		Properties props = ConfigUtil.getProps("cache-config.properties");
		pool = new JedisPool(poolConfig, props.getProperty("redis.mq.server"), Integer.parseInt(props
				.getProperty("redis.mq.port")), 30_000, props.getProperty("redis.password"));
	}

	public synchronized static Jedis getJedis() {
		try {
			if (pool != null) {
				return pool.getResource();
			} else {
				return null;
			}
		} catch (Exception e) {
			logger.error("Exception:{}", LogbackUtil.expection2Str(e));
			return null;
		}
	}

	/**
	 * 添加数据
	 */
	public void addRecord(String... members) {
		Jedis jedis = getJedis();
		if (jedis == null) {
			return;
		}
		try {
			jedis.watch(CACHE_SENTIMENT_KEY);
			Transaction tx = jedis.multi();
			tx.sadd(CACHE_SENTIMENT_KEY, members);
			tx.exec();
			jedis.unwatch();
			// pipeline适用于批处理，管道比事务效率高
			// 不使用dsicard会出现打开文件数太多，使用的话DISCARD without MULTI。
			//			Pipeline p = jedis.pipelined();
			//			p.sadd(CACHE_SENTIMENT_KEY, members);
			//			p.sync();// 关闭pipeline
		} catch (Exception e) {
			logger.error("Exception:{}", LogbackUtil.expection2Str(e));
			if (jedis != null) {
				pool.returnBrokenResource(jedis);
				jedis = null;
			}
		} finally {
			// 这里很重要，一旦拿到的jedis实例使用完毕，必须要返还给池中 
			if (jedis != null && jedis.isConnected())
				pool.returnResource(jedis);
		}
	}

	/**
	 * 获取集合大小
	 */
	public long getSetSize() {
		long result = 0L;
		Jedis jedis = getJedis();
		if (jedis == null) {
			return result;
		}
		try {
			// 在事务和管道中不支持同步查询
			result = jedis.scard(CACHE_SENTIMENT_KEY).longValue();
		} catch (Exception e) {
			logger.error("Exception:{}", LogbackUtil.expection2Str(e));
			if (jedis != null) {
				pool.returnBrokenResource(jedis);
				jedis = null;
			}
		} finally {
			if (jedis != null && jedis.isConnected())
				pool.returnResource(jedis);
		}
		return result;
	}

	/**
	 * 获取数据
	 */
	public List<String> getRecords() {
		List<String> records = new ArrayList<>();
		Jedis jedis = getJedis();
		if (jedis == null) {
			return records;
		}
		try {
			String value = jedis.spop(CACHE_SENTIMENT_KEY);
			while (value != null) {
				records.add(value);
				value = jedis.spop(CACHE_SENTIMENT_KEY);
			}
			logger.info("Records'size = {}", records.size());
		} catch (Exception e) {
			logger.error("Exception:{}", LogbackUtil.expection2Str(e));
			if (jedis != null) {
				pool.returnBrokenResource(jedis);
				jedis = null;
			}
		} finally {
			if (jedis != null && jedis.isConnected())
				pool.returnResource(jedis);
		}
		return records;
	}

	/**
	 * 将数据从String映射到Object
	 */
	public List<RecordInfo> mapper(List<String> records) {
		List<RecordInfo> recordInfos = new ArrayList<>();
		for (String record : records) {
			try {
				recordInfos.add(OBJECT_MAPPER.readValue(record, RecordInfo.class));
			} catch (IOException e) {
				logger.error("Record:{}", record);
				logger.error("Exception:{}", LogbackUtil.expection2Str(e));
			}
		}
		return recordInfos;
	}

	public void close() {
		// 程序关闭时，需要调用关闭方法 
		pool.destroy();
	}

}

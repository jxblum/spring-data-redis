/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.keyvalue.core.AbstractKeyValueAdapter;
import org.springframework.data.keyvalue.core.KeyValueAdapter;
import org.springframework.data.keyvalue.core.mapping.KeyValuePersistentProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.convert.IndexResolverImpl;
import org.springframework.data.redis.core.convert.MappingRedisConverter;
import org.springframework.data.redis.core.convert.RedisConverter;
import org.springframework.data.redis.core.convert.RedisData;
import org.springframework.data.redis.core.convert.ReferenceResolverImpl;
import org.springframework.data.redis.core.mapping.RedisMappingContext;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.data.util.CloseableIterator;
import org.springframework.util.Assert;

/**
 * Redis specific {@link KeyValueAdapter} implementation. Uses binary codec to read/write data from/to Redis.
 * 
 * @author Christoph Strobl
 */
public class RedisKeyValueAdapter extends AbstractKeyValueAdapter implements ApplicationContextAware,
		ApplicationListener<RedisKeyspaceEvent> {

	private static final Logger LOGGER = LoggerFactory.getLogger(RedisKeyValueAdapter.class);

	private RedisOperations<?, ?> redisOps;
	private MappingRedisConverter converter;
	private RedisMessageListenerContainer messageListenerContainer;
	private KeyExpirationEventMessageListener expirationListener;

	/**
	 * @param redisOps must not be {@literal null}.
	 * @param mappingContext must not be {@literal null}.
	 */
	public RedisKeyValueAdapter(RedisOperations<?, ?> redisOps, RedisMappingContext mappingContext) {

		super(new RedisQueryEngine());

		Assert.notNull(redisOps, "RedisOperations must not be null!");
		Assert.notNull(mappingContext, "RedisMappingContext must not be null!");

		converter = new MappingRedisConverter(mappingContext, new IndexResolverImpl(mappingContext
				.getMappingConfiguration().getIndexConfiguration()), new ReferenceResolverImpl(this));
		this.redisOps = redisOps;

		initKeyExpirationListener();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#put(java.io.Serializable, java.lang.Object, java.io.Serializable)
	 */
	public Object put(final Serializable id, final Object item, final Serializable keyspace) {

		final RedisData rdo = item instanceof RedisData ? (RedisData) item : new RedisData();
		if (!(item instanceof RedisData)) {
			converter.write(item, rdo);
		}

		if (rdo.getId() == null) {

			rdo.setId(id);

			if (!(item instanceof RedisData)) {
				KeyValuePersistentProperty idProperty = converter.getMappingContext().getPersistentEntity(item.getClass())
						.getIdProperty();
				converter.getMappingContext().getPersistentEntity(item.getClass()).getPropertyAccessor(item)
						.setProperty(idProperty, id);
			}
		}

		redisOps.execute(new RedisCallback<Object>() {

			@Override
			public Object doInRedis(RedisConnection connection) throws DataAccessException {

				byte[] key = converter.toBytes(rdo.getId());
				byte[] objectKey = createKey(rdo.getKeyspace(), rdo.getId());

				connection.del(objectKey);

				connection.hMSet(objectKey, rdo.getBucket().rawMap());

				if (rdo.getTimeToLive() != null && rdo.getTimeToLive().longValue() > 0) {

					connection.expire(objectKey, rdo.getTimeToLive().longValue());

					// add phantom key so values can be restored
					byte[] phantomKey = ByteUtils.concat(objectKey, converter.toBytes(":phantom"));
					connection.del(phantomKey);
					connection.hMSet(phantomKey, rdo.getBucket().rawMap());
					connection.expire(phantomKey, rdo.getTimeToLive().longValue() + 300);
				}

				connection.sAdd(converter.toBytes(rdo.getKeyspace()), key);

				new IndexWriter(connection, converter).updateIndexes(key, rdo.getIndexedData());
				return null;
			}
		});

		return item;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#contains(java.io.Serializable, java.io.Serializable)
	 */
	public boolean contains(final Serializable id, final Serializable keyspace) {

		Boolean exists = redisOps.execute(new RedisCallback<Boolean>() {

			@Override
			public Boolean doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.sIsMember(converter.toBytes(keyspace), converter.toBytes(id));
			}
		});

		return exists != null ? exists.booleanValue() : false;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#get(java.io.Serializable, java.io.Serializable)
	 */
	public Object get(Serializable id, Serializable keyspace) {

		final byte[] binId = createKey(keyspace, id);

		Map<byte[], byte[]> raw = redisOps.execute(new RedisCallback<Map<byte[], byte[]>>() {

			@Override
			public Map<byte[], byte[]> doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.hGetAll(binId);
			}
		});

		return converter.read(Object.class, new RedisData(raw));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#delete(java.io.Serializable, java.io.Serializable)
	 */
	public Object delete(final Serializable id, final Serializable keyspace) {

		final byte[] binId = converter.toBytes(id);
		final byte[] binKeyspace = converter.toBytes(keyspace);

		Object o = get(id, keyspace);

		if (o != null) {

			redisOps.execute(new RedisCallback<Void>() {

				@Override
				public Void doInRedis(RedisConnection connection) throws DataAccessException {

					connection.del(createKey(keyspace, id));
					connection.sRem(binKeyspace, binId);

					new IndexWriter(connection, converter).removeKeyFromIndexes(keyspace.toString(), binId);
					return null;
				}
			});

		}
		return o;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#getAllOf(java.io.Serializable)
	 */
	public List<?> getAllOf(final Serializable keyspace) {

		final byte[] binKeyspace = converter.toBytes(keyspace);

		List<Map<byte[], byte[]>> raw = redisOps.execute(new RedisCallback<List<Map<byte[], byte[]>>>() {

			@Override
			public List<Map<byte[], byte[]>> doInRedis(RedisConnection connection) throws DataAccessException {

				final List<Map<byte[], byte[]>> rawData = new ArrayList<Map<byte[], byte[]>>();

				Set<byte[]> members = connection.sMembers(binKeyspace);

				for (byte[] id : members) {
					rawData.add(connection.hGetAll(createKey(binKeyspace, id)));
				}

				return rawData;
			}
		});

		List<Object> result = new ArrayList<Object>(raw.size());
		for (Map<byte[], byte[]> rawData : raw) {
			result.add(converter.read(Object.class, new RedisData(rawData)));
		}

		return result;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#deleteAllOf(java.io.Serializable)
	 */
	public void deleteAllOf(final Serializable keyspace) {

		redisOps.execute(new RedisCallback<Void>() {

			@Override
			public Void doInRedis(RedisConnection connection) throws DataAccessException {

				connection.del(converter.toBytes(keyspace));
				new IndexWriter(connection, converter).removeAllIndexes(keyspace.toString());
				return null;
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#entries(java.io.Serializable)
	 */
	public CloseableIterator<Entry<Serializable, Object>> entries(Serializable keyspace) {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#count(java.io.Serializable)
	 */
	public long count(final Serializable keyspace) {

		Long count = redisOps.execute(new RedisCallback<Long>() {

			@Override
			public Long doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.sCard(converter.toBytes(keyspace));
			}
		});

		return count != null ? count.longValue() : 0;
	}

	/**
	 * Execute {@link RedisCallback} via underlying {@link RedisOperations}.
	 * 
	 * @param callback must not be {@literal null}.
	 * @see RedisOperations#execute(RedisCallback)
	 * @return
	 */
	public <T> T execute(RedisCallback<T> callback) {
		return redisOps.execute(callback);
	}

	/**
	 * Get the {@link RedisConverter} in use.
	 * 
	 * @return never {@literal null}.
	 */
	public RedisConverter getConverter() {
		return this.converter;
	}

	public void clear() {
		// nothing to do
	}

	public byte[] createKey(Serializable keyspace, Serializable id) {
		return this.converter.toBytes(keyspace + ":" + id);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	public void destroy() throws Exception {

		if (redisOps instanceof RedisTemplate) {
			RedisConnectionFactory connectionFactory = ((RedisTemplate<?, ?>) redisOps).getConnectionFactory();
			if (connectionFactory instanceof DisposableBean) {
				((DisposableBean) connectionFactory).destroy();
			}
		}

		this.expirationListener.destroy();
		this.messageListenerContainer.destroy();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
	 */
	@Override
	public void onApplicationEvent(RedisKeyspaceEvent event) {

		LOGGER.debug("Received %s .", event);

		if (event instanceof RedisKeyExpiredEvent) {

			final RedisKeyExpiredEvent expiredEvent = (RedisKeyExpiredEvent) event;

			redisOps.execute(new RedisCallback<Void>() {

				@Override
				public Void doInRedis(RedisConnection connection) throws DataAccessException {

					LOGGER.debug("Cleaning up expired key '%s' data structures in keyspace '%s'.", expiredEvent.getSource(),
							expiredEvent.getKeyspace());

					connection.sRem(converter.toBytes(expiredEvent.getKeyspace()), expiredEvent.getId());
					new IndexWriter(connection, converter).removeKeyFromIndexes(expiredEvent.getKeyspace(), expiredEvent.getId());
					return null;
				}
			});

		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.expirationListener.setApplicationEventPublisher(applicationContext);
	}

	private void initKeyExpirationListener() {

		this.messageListenerContainer = new RedisMessageListenerContainer();
		messageListenerContainer.setConnectionFactory(((RedisTemplate<?, ?>) redisOps).getConnectionFactory());
		messageListenerContainer.afterPropertiesSet();
		messageListenerContainer.start();

		this.expirationListener = new MappingExpirationListener(this.messageListenerContainer, this.redisOps,
				this.converter);
		this.expirationListener.init();
	}

	/**
	 * @author Christoph Strobl
	 */
	static class MappingExpirationListener extends KeyExpirationEventMessageListener {

		private final RedisOperations<?, ?> ops;
		private final RedisConverter converter;

		public MappingExpirationListener(RedisMessageListenerContainer listenerContainer, RedisOperations<?, ?> ops,
				RedisConverter converter) {

			super(listenerContainer);
			this.ops = ops;
			this.converter = converter;
		}

		@Override
		public void onMessage(Message message, byte[] pattern) {

			if (!isKeyExpirationMessage(message)) {
				return;
			}

			byte[] key = message.getBody();

			final byte[] phantomKey = ByteUtils.concat(key, converter.getConversionService()
					.convert(":phantom", byte[].class));

			Map<byte[], byte[]> hash = ops.execute(new RedisCallback<Map<byte[], byte[]>>() {

				@Override
				public Map<byte[], byte[]> doInRedis(RedisConnection connection) throws DataAccessException {

					Map<byte[], byte[]> hash = connection.hGetAll(phantomKey);

					if (!org.springframework.util.CollectionUtils.isEmpty(hash)) {
						connection.del(phantomKey);
					}
					return hash;
				}
			});

			Object value = converter.read(Object.class, new RedisData(hash));
			publishEvent(new RedisKeyExpiredEvent(key, value));
		}

		private boolean isKeyExpirationMessage(Message message) {

			if (message == null || message.getChannel() == null || message.getBody() == null) {
				return false;
			}

			byte[][] args = ByteUtils.split(message.getBody(), ':');
			if (args.length != 2) {
				return false;
			}

			return true;
		}
	}

}

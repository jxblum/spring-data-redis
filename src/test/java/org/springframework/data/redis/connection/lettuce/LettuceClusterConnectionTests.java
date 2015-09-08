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
package org.springframework.data.redis.connection.lettuce;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.data.redis.connection.DefaultSortParameters;
import org.springframework.data.redis.connection.DefaultTuple;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisListCommands.Position;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisZSetCommands.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Tuple;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisURI.Builder;
import com.lambdaworks.redis.cluster.RedisAdvancedClusterConnection;
import com.lambdaworks.redis.cluster.RedisClusterClient;

/**
 * @author Christoph Strobl
 */
public class LettuceClusterConnectionTests {

	static final String KEY_1 = "key1";
	static final String KEY_2 = "key2";
	static final String KEY_3 = "key3";

	static final byte[] KEY_1_BYTES = LettuceConverters.toBytes(KEY_1);
	static final byte[] KEY_2_BYTES = LettuceConverters.toBytes(KEY_2);
	static final byte[] KEY_3_BYTES = LettuceConverters.toBytes(KEY_3);

	static final String VALUE_1 = "value1";
	static final String VALUE_2 = "value2";
	static final String VALUE_3 = "value3";

	static final byte[] VALUE_1_BYTES = LettuceConverters.toBytes(VALUE_1);
	static final byte[] VALUE_2_BYTES = LettuceConverters.toBytes(VALUE_2);
	static final byte[] VALUE_3_BYTES = LettuceConverters.toBytes(VALUE_3);

	RedisClusterClient client;
	RedisAdvancedClusterConnection<String, String> nativeConnection;
	LettuceClusterConnection clusterConnection;

	@BeforeClass
	public static void beforeClass() {

		RedisClient client = new RedisClient("127.0.0.1", 7379);
		String mode = LettuceConverters.toProperties(client.connect().info()).getProperty("redis_mode");
		client.shutdown(0, 0, TimeUnit.MILLISECONDS);

		assumeThat(mode, is("cluster"));
	}

	@Before
	public void setUp() {

		client = new RedisClusterClient(Builder.redis("127.0.0.1", 7379).withTimeout(100, TimeUnit.MILLISECONDS).build());
		nativeConnection = client.connectCluster();
		clusterConnection = new LettuceClusterConnection(client);
	}

	@After
	public void tearDown() throws InterruptedException {

		clusterConnection.flushDb();
		nativeConnection.close();
		clusterConnection.close();
		client.shutdown(0, 0, TimeUnit.MILLISECONDS);
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void shouldAllowSettingAndGettingValues() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES);
		assertThat(clusterConnection.get(KEY_1_BYTES), is(VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void delShouldRemoveSingleKeyCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.del(KEY_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), nullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void delShouldRemoveMultipleKeysCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		clusterConnection.del(KEY_1_BYTES);
		clusterConnection.del(KEY_2_BYTES);

		assertThat(nativeConnection.get(KEY_1), nullValue());
		assertThat(nativeConnection.get(KEY_2), nullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void keysShouldReturnAllKeys() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.keys(LettuceConverters.toBytes("*")), hasItems(KEY_1_BYTES, KEY_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void randomKeyShouldReturnCorrectlyWhenKeysAvailable() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.randomKey(), notNullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void randomKeyShouldReturnNullWhenNoKeysAvailable() {
		assertThat(clusterConnection.randomKey(), nullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void expireShouldBeSetCorreclty() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.expire(KEY_1_BYTES, 5);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pExpireShouldBeSetCorreclty() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.pExpire(KEY_1_BYTES, 5000);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void expireAtShouldBeSetCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.expireAt(KEY_1_BYTES, System.currentTimeMillis() / 1000 + 5000);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pExpireAtShouldBeSetCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.pExpireAt(KEY_1_BYTES, System.currentTimeMillis() + 5000);

		assertThat(nativeConnection.ttl(LettuceConverters.toString(KEY_1_BYTES)) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void dbSizeShouldReturnCummulatedDbSize() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.dbSize(), is(2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void dbSizeForSpecificNodeShouldGetNodeDbSize() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		assertThat(clusterConnection.dbSize(new RedisClusterNode("127.0.0.1", 7379, null)), is(1L));
		assertThat(clusterConnection.dbSize(new RedisClusterNode("127.0.0.1", 7380, null)), is(1L));
		assertThat(clusterConnection.dbSize(new RedisClusterNode("127.0.0.1", 7381, null)), is(0L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test(expected = UnsupportedOperationException.class)
	public void moveShouldThrowExeption() {
		clusterConnection.move("".getBytes(), 2);
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void ttlShouldReturnMinusTwoWhenKeyDoesNotExist() {
		assertThat(clusterConnection.ttl(KEY_1_BYTES), is(-2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void ttlShouldReturnMinusOneWhenKeyDoesNotHaveExpirationSet() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.ttl(KEY_1_BYTES), is(-1L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void ttlShouldReturnValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.expire(KEY_1, 5);

		assertThat(clusterConnection.ttl(KEY_1_BYTES) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pTtlShouldReturnMinusTwoWhenKeyDoesNotExist() {
		assertThat(clusterConnection.pTtl(KEY_1_BYTES), is(-2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pTtlShouldReturnMinusOneWhenKeyDoesNotHaveExpirationSet() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.pTtl(KEY_1_BYTES), is(-1L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pTtlShouldReturValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.expire(KEY_1, 5);

		assertThat(clusterConnection.pTtl(KEY_1_BYTES) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	@Ignore("check this one later - does not work")
	public void sortShouldReturValuesCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_2, VALUE_1);

		assertThat(clusterConnection.sort(KEY_1_BYTES, new DefaultSortParameters().asc()),
				hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void dumpAndRestoreShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		byte[] dumpedValue = clusterConnection.dump(KEY_1_BYTES);
		clusterConnection.restore(KEY_2_BYTES, 0, dumpedValue);

		assertThat(nativeConnection.get(KEY_2), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void getShouldReturnValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.get(KEY_1_BYTES), is(VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void getSetShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		byte[] valueBeforeSet = clusterConnection.getSet(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(valueBeforeSet, is(VALUE_1_BYTES));
		assertThat(nativeConnection.get(KEY_1), is(VALUE_2));
	}

	@Test
	@Ignore
	public void mGetShouldReturnCorrectlyWhenKeysMapToSameSlot() {
		// TODO manually map keys to a fixed same slot
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void setShouldSetValueCorrectly() {

		clusterConnection.set(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void setNxShouldSetValueCorrectly() {

		clusterConnection.setNX(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void setNxShouldNotSetValueWhenAlreadyExistsInDBCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.setNX(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void setExShouldSetValueCorrectly() {

		clusterConnection.setEx(KEY_1_BYTES, 5, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.ttl(KEY_1) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pSetExShouldSetValueCorrectly() {

		clusterConnection.pSetEx(KEY_1_BYTES, 5000, VALUE_1_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1));
		assertThat(nativeConnection.ttl(KEY_1) > 1, is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	@Ignore
	public void mSetShouldWorkOnceKeysMapToSameSlot() {

	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	@Ignore
	public void mSetNXShouldWorkOnceKeysMapToSameSlot() {

	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void incrShouldIncreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "1");

		assertThat(clusterConnection.incr(KEY_1_BYTES), is(2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void incrByShouldIncreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "1");

		assertThat(clusterConnection.incrBy(KEY_1_BYTES, 5), is(6L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void decrShouldDecreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "5");

		assertThat(clusterConnection.decr(KEY_1_BYTES), is(4L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void decrByShouldDecreaseValueCorrectly() {

		nativeConnection.set(KEY_1, "5");

		assertThat(clusterConnection.decrBy(KEY_1_BYTES, 4), is(1L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void appendShouldAddValueCorrectly() {

		clusterConnection.append(KEY_1_BYTES, VALUE_1_BYTES);
		clusterConnection.append(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.get(KEY_1), is(VALUE_1.concat(VALUE_2)));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void getRangeShouldReturnValueCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.getRange(KEY_1_BYTES, 0, 2), is(LettuceConverters.toBytes("val")));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void setRangeShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		clusterConnection.setRange(KEY_1_BYTES, LettuceConverters.toBytes("UE"), 3);

		assertThat(nativeConnection.get(KEY_1), is("valUE1"));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void strLenShouldWorkCorrectly() {

		nativeConnection.set(KEY_1, VALUE_1);

		assertThat(clusterConnection.strLen(KEY_1_BYTES), is(6L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void rPushShoultAddValuesCorrectly() {

		clusterConnection.rPush(KEY_1_BYTES, VALUE_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1), hasItems(VALUE_1, VALUE_2));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lPushShoultAddValuesCorrectly() {

		clusterConnection.lPush(KEY_1_BYTES, VALUE_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1), hasItems(VALUE_1, VALUE_2));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void rPushNXShoultNotAddValuesWhenKeyDoesNotExist() {

		clusterConnection.rPushX(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.exists(KEY_1), is(false));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lPushNXShoultNotAddValuesWhenKeyDoesNotExist() {

		clusterConnection.lPushX(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.exists(KEY_1), is(false));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lLenShouldCountValuesCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.lLen(KEY_1_BYTES), is(2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lRangeShouldGetValuesCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.lRange(KEY_1_BYTES, 0L, -1L), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lTrimShouldTrimListCorrectly() {

		nativeConnection.lpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lTrim(KEY_1_BYTES, 2, 3);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1), hasItems(VALUE_1, VALUE_2));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lIndexShouldGetElementAtIndexCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		assertThat(clusterConnection.lIndex(KEY_1_BYTES, 1), is(VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lInsertShouldAddElementAtPositionCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lInsert(KEY_1_BYTES, Position.AFTER, VALUE_2_BYTES, LettuceConverters.toBytes("booh!"));

		assertThat(nativeConnection.lrange(KEY_1, 0, -1).get(2), is("booh!"));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lSetShouldSetElementAtPositionCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lSet(KEY_1_BYTES, 1L, VALUE_1_BYTES);

		assertThat(nativeConnection.lrange(KEY_1, 0, -1).get(1), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lRemShouldRemoveElementAtPositionCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2, "foo", "bar");

		clusterConnection.lRem(KEY_1_BYTES, 1L, VALUE_1_BYTES);

		assertThat(nativeConnection.llen(KEY_1), is(3L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void lPopShouldReturnElementCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.lPop(KEY_1_BYTES), is(VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void rPopShouldReturnElementCorrectly() {

		nativeConnection.rpush(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.rPop(KEY_1_BYTES), is(VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	@Ignore
	public void rPopLPushShouldWorkWhenKeysMapToSameSlot() {
		// TODO check slots and assing manually
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	@Ignore
	public void bRPopLPushShouldWorkWhenKeysMapToSameSlot() {
		// TODO check slots and assing manually
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sAddShouldAddValueToSetCorrectly() {

		clusterConnection.sAdd(KEY_1_BYTES, VALUE_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.smembers(KEY_1), hasItems(VALUE_1, VALUE_2));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sRemShouldRemoveValueFromSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		clusterConnection.sRem(KEY_1_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.smembers(KEY_1), hasItems(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sPopShouldPopValueFromSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sPop(KEY_1_BYTES), notNullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	@Ignore
	public void sMoveShouldWorkWhenKeysMapToSameSlot() {
		// TODO check slots and assing manually
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sCardShouldCountValuesInSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sCard(KEY_1_BYTES), is(2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sIsMemberShouldReturnTrueIfValueIsMemberOfSet() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sIsMember(KEY_1_BYTES, VALUE_1_BYTES), is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sIsMemberShouldReturnFalseIfValueIsMemberOfSet() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sIsMember(KEY_1_BYTES, LettuceConverters.toBytes("foo")), is(false));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sMembersShouldReturnValuesContainedInSetCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sMembers(KEY_1_BYTES), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sRandMamberShouldReturnValueCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sRandMember(KEY_1_BYTES), notNullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sRandMamberWithCountShouldReturnValueCorrectly() {

		nativeConnection.sadd(KEY_1, VALUE_1, VALUE_2);

		assertThat(clusterConnection.sRandMember(KEY_1_BYTES, 3), notNullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void sscanShouldRetrieveAllValuesInSetCorrectly() {

		for (int i = 0; i < 30; i++) {
			nativeConnection.sadd(KEY_1, Integer.valueOf(i).toString());
		}

		int count = 0;
		Cursor<byte[]> cursor = clusterConnection.sScan(KEY_1_BYTES, ScanOptions.NONE);
		while (cursor.hasNext()) {
			count++;
			cursor.next();
		}

		assertThat(count, is(30));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zAddShouldAddValueWithScoreCorrectly() {

		clusterConnection.zAdd(KEY_1_BYTES, 10D, VALUE_1_BYTES);
		clusterConnection.zAdd(KEY_1_BYTES, 20D, VALUE_2_BYTES);

		assertThat(nativeConnection.zcard(KEY_1), is(2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRemShouldRemoveValueWithScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		clusterConnection.zRem(KEY_1_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.zcard(KEY_1), is(1L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zIncrByShouldIncScoreForValueCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		clusterConnection.zIncrBy(KEY_1_BYTES, 100D, VALUE_1_BYTES);

		assertThat(nativeConnection.zrank(KEY_1, VALUE_1), is(1L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRankShouldReturnPositionForValueCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		assertThat(clusterConnection.zRank(KEY_1_BYTES, VALUE_2_BYTES), is(1L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRankShouldReturnReversePositionForValueCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		assertThat(clusterConnection.zRevRank(KEY_1_BYTES, VALUE_2_BYTES), is(0L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRangeShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRange(KEY_1_BYTES, 1, 2), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRangeWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeWithScores(KEY_1_BYTES, 1, 2),
				hasItems((Tuple) new DefaultTuple(VALUE_1_BYTES, 10D), (Tuple) new DefaultTuple(VALUE_2_BYTES, 20D)));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRangeByScoreShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScore(KEY_1_BYTES, 10, 20), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRangeByScoreWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScoreWithScores(KEY_1_BYTES, 10, 20),
				hasItems((Tuple) new DefaultTuple(VALUE_1_BYTES, 10D), (Tuple) new DefaultTuple(VALUE_2_BYTES, 20D)));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRangeByScoreShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScore(KEY_1_BYTES, 10D, 20D, 0L, 1L), hasItems(VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRangeByScoreWithScoresShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRangeByScoreWithScores(KEY_1_BYTES, 10D, 20D, 0L, 1L),
				hasItems((Tuple) new DefaultTuple(VALUE_1_BYTES, 10D)));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRevRangeShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRange(KEY_1_BYTES, 1, 2), hasItems(VALUE_3_BYTES, VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRevRangeWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeWithScores(KEY_1_BYTES, 1, 2),
				hasItems((Tuple) new DefaultTuple(VALUE_3_BYTES, 5D), (Tuple) new DefaultTuple(VALUE_1_BYTES, 10D)));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRevRangeByScoreShouldReturnValuesCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScore(KEY_1_BYTES, 10D, 20D), hasItems(VALUE_2_BYTES, VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRevRangeByScoreWithScoresShouldReturnValuesAndScoreCorrectly() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScoreWithScores(KEY_1_BYTES, 10D, 20D),
				hasItems((Tuple) new DefaultTuple(VALUE_2_BYTES, 20D), (Tuple) new DefaultTuple(VALUE_1_BYTES, 10D)));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRevRangeByScoreShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScore(KEY_1_BYTES, 10D, 20D, 0L, 1L), hasItems(VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRevRangeByScoreWithScoresShouldReturnValuesCorrectlyWhenGivenOffsetAndScore() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zRevRangeByScoreWithScores(KEY_1_BYTES, 10D, 20D, 0L, 1L),
				hasItems((Tuple) new DefaultTuple(VALUE_2_BYTES, 20D)));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zCountShouldCountValuesInRange() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zCount(KEY_1_BYTES, 10, 20), is(2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zCardShouldReturnTotalNumberOfValues() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);
		nativeConnection.zadd(KEY_1, 5D, VALUE_3);

		assertThat(clusterConnection.zCard(KEY_1_BYTES), is(3L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zScoreShouldRetrieveScoreForValue() {

		nativeConnection.zadd(KEY_1, 10D, VALUE_1);
		nativeConnection.zadd(KEY_1, 20D, VALUE_2);

		assertThat(clusterConnection.zScore(KEY_1_BYTES, VALUE_2_BYTES), is(20D));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hSetShouldSetValueCorrectly() {

		clusterConnection.hSet(KEY_1_BYTES, KEY_2_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.hget(KEY_1, KEY_2), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hSetNXShouldSetValueCorrectly() {

		clusterConnection.hSetNX(KEY_1_BYTES, KEY_2_BYTES, VALUE_1_BYTES);

		assertThat(nativeConnection.hget(KEY_1, KEY_2), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hSetNXShouldNotSetValueWhenAlreadyExists() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);

		clusterConnection.hSetNX(KEY_1_BYTES, KEY_2_BYTES, VALUE_2_BYTES);

		assertThat(nativeConnection.hget(KEY_1, KEY_2), is(VALUE_1));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hGetShouldRetrieveValueCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);

		assertThat(clusterConnection.hGet(KEY_1_BYTES, KEY_2_BYTES), is(VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hMGetShouldRetrieveValueCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hMGet(KEY_1_BYTES, KEY_2_BYTES, KEY_3_BYTES), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hMSetShouldAddValuesCorrectly() {

		Map<byte[], byte[]> hashes = new HashMap<byte[], byte[]>();
		hashes.put(KEY_2_BYTES, VALUE_1_BYTES);
		hashes.put(KEY_3_BYTES, VALUE_2_BYTES);

		clusterConnection.hMSet(KEY_1_BYTES, hashes);

		assertThat(nativeConnection.hmget(KEY_1, KEY_2, KEY_3), hasItems(VALUE_1, VALUE_2));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hExistsShouldReturnPresenceOfFieldCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);

		assertThat(clusterConnection.hExists(KEY_1_BYTES, KEY_2_BYTES), is(true));
		assertThat(clusterConnection.hExists(KEY_1_BYTES, KEY_3_BYTES), is(false));
		assertThat(clusterConnection.hExists(LettuceConverters.toBytes("foo"), KEY_2_BYTES), is(false));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hDelShouldRemoveFieldsCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		clusterConnection.hDel(KEY_1_BYTES, KEY_2_BYTES);

		assertThat(nativeConnection.hexists(KEY_1, KEY_2), is(false));
		assertThat(nativeConnection.hexists(KEY_1, KEY_3), is(true));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hLenShouldRetrieveSizeCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hLen(KEY_1_BYTES), is(2L));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hKeysShouldRetrieveKeysCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hKeys(KEY_1_BYTES), hasItems(KEY_2_BYTES, KEY_3_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hValsShouldRetrieveValuesCorrectly() {

		nativeConnection.hset(KEY_1, KEY_2, VALUE_1);
		nativeConnection.hset(KEY_1, KEY_3, VALUE_2);

		assertThat(clusterConnection.hVals(KEY_1_BYTES), hasItems(VALUE_1_BYTES, VALUE_2_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void hGetAllShouldRetrieveEntriesCorrectly() {

		Map<String, String> hashes = new HashMap<String, String>();
		hashes.put(KEY_2, VALUE_1);
		hashes.put(KEY_3, VALUE_2);

		nativeConnection.hmset(KEY_1, hashes);

		Map<byte[], byte[]> hGetAll = clusterConnection.hGetAll(KEY_1_BYTES);

		assertThat(hGetAll.keySet(), hasItems(KEY_2_BYTES, KEY_3_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void echoShouldReturnInputCorrectly() {
		assertThat(clusterConnection.echo(VALUE_1_BYTES), is(VALUE_1_BYTES));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pingShouldRetrunPongForExistingNode() {
		assertThat(clusterConnection.ping(new RedisClusterNode("127.0.0.1", 7379, null)), is("PONG"));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void pingShouldRetrunPong() {
		assertThat(clusterConnection.ping(), is("PONG"));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test(expected = IllegalArgumentException.class)
	public void pingShouldThrowExceptionWhenNodeNotKnownToCluster() {
		clusterConnection.ping(new RedisClusterNode("127.0.0.1", 1234, null));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void flushDbShouldFlushAllClusterNodes() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		clusterConnection.flushDb();

		assertThat(nativeConnection.get(KEY_1), nullValue());
		assertThat(nativeConnection.get(KEY_2), nullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Ignore("TODO")
	public void flushDbOnSingleNodeShouldFlushOnlyGivenNodesDd() {

		nativeConnection.set(KEY_1, VALUE_1);
		nativeConnection.set(KEY_2, VALUE_2);

		clusterConnection.flushDb(new RedisClusterNode("127.0.0.1", 7379, null));

		assertThat(nativeConnection.get(KEY_1), notNullValue());
		assertThat(nativeConnection.get(KEY_2), nullValue());
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void zRangeByLexShouldReturnResultCorrectly() {

		nativeConnection.zadd(KEY_1, 0, "a");
		nativeConnection.zadd(KEY_1, 0, "b");
		nativeConnection.zadd(KEY_1, 0, "c");
		nativeConnection.zadd(KEY_1, 0, "d");
		nativeConnection.zadd(KEY_1, 0, "e");
		nativeConnection.zadd(KEY_1, 0, "f");
		nativeConnection.zadd(KEY_1, 0, "g");

		Set<byte[]> values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().lte("c"));

		assertThat(values,
				hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("b"), LettuceConverters.toBytes("c")));
		assertThat(
				values,
				not(hasItems(LettuceConverters.toBytes("d"), LettuceConverters.toBytes("e"), LettuceConverters.toBytes("f"),
						LettuceConverters.toBytes("g"))));

		values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().lt("c"));
		assertThat(values, hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("b")));
		assertThat(values, not(hasItem(LettuceConverters.toBytes("c"))));

		values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().gte("aaa").lt("g"));
		assertThat(
				values,
				hasItems(LettuceConverters.toBytes("b"), LettuceConverters.toBytes("c"), LettuceConverters.toBytes("d"),
						LettuceConverters.toBytes("e"), LettuceConverters.toBytes("f")));
		assertThat(values, not(hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("g"))));

		values = clusterConnection.zRangeByLex(KEY_1_BYTES, Range.range().gte("e"));
		assertThat(values,
				hasItems(LettuceConverters.toBytes("e"), LettuceConverters.toBytes("f"), LettuceConverters.toBytes("g")));
		assertThat(
				values,
				not(hasItems(LettuceConverters.toBytes("a"), LettuceConverters.toBytes("b"), LettuceConverters.toBytes("c"),
						LettuceConverters.toBytes("d"))));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void infoShouldCollectionInfoFromAllClusterNodes() {
		assertThat(clusterConnection.info().size(), is(3));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void clientListShouldGetInfosForAllClients() {
		assertThat(clusterConnection.getClientList().isEmpty(), is(false));
	}

	/**
	 * @see DATAREDIS-315
	 */
	@Test
	public void getClusterNodeForKeyShouldReturnNodeCorrectly() {
		assertThat((RedisNode) clusterConnection.getClusterNodeForKey(KEY_1_BYTES), is(new RedisNode("127.0.0.1", 7380)));
	}
}

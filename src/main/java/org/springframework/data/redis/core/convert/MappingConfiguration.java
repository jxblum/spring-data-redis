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
package org.springframework.data.redis.core.convert;

import org.springframework.data.redis.core.index.IndexConfiguration;

/**
 * @author Christoph Strobl
 */
public class MappingConfiguration {

	private final IndexConfiguration indexConfiguration;
	private final KeyspaceConfiguration keyspaceConfiguration;

	public MappingConfiguration(IndexConfiguration indexConfiguration, KeyspaceConfiguration keyspaceConfiguration) {

		this.indexConfiguration = indexConfiguration;
		this.keyspaceConfiguration = keyspaceConfiguration;
	}

	public IndexConfiguration getIndexConfiguration() {
		return indexConfiguration;
	}

	public KeyspaceConfiguration getKeyspaceConfiguration() {
		return keyspaceConfiguration;
	}
}

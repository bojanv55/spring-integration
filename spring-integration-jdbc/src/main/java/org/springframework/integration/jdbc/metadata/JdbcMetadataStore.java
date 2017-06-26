/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.integration.jdbc.metadata;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.integration.metadata.MetadataStore;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Jdbc-based implementation of {@link MetadataStore}.
 *
 * @author Bojan Vukasovic
 * @since 5.0
 */
public class JdbcMetadataStore implements ConcurrentMetadataStore {

	/**
	 * Default value for the table prefix property.
	 */
	public static final String DEFAULT_TABLE_PREFIX = "INT_";

	private volatile String tablePrefix = DEFAULT_TABLE_PREFIX;

	private final JdbcOperations jdbcTemplate;

	private volatile Map<Query, String> queryCache = new HashMap<>();

	private enum Query {
		GET_VALUE("SELECT METADATA_VALUE FROM %PREFIX%METADATA_STORE WHERE METADATA_KEY=?"),
		GET_VALUE_FOR_UPDATE("SELECT METADATA_VALUE FROM %PREFIX%METADATA_STORE WHERE METADATA_KEY=? FOR UPDATE"),
		REPLACE_VALUE("UPDATE %PREFIX%METADATA_STORE SET METADATA_VALUE=? WHERE METADATA_KEY=? AND METADATA_VALUE=?"),
		REPLACE_VALUE_BY_KEY("UPDATE %PREFIX%METADATA_STORE SET METADATA_VALUE=? WHERE METADATA_KEY=?"),
		REMOVE_VALUE("DELETE FROM %PREFIX%METADATA_STORE WHERE METADATA_KEY=?"),
		PUT_IF_ABSENT_VALUE("INSERT INTO %PREFIX%METADATA_STORE(METADATA_KEY, METADATA_VALUE) "
				+ "SELECT ?, ? FROM INT_METADATA_STORE WHERE METADATA_KEY=? HAVING COUNT(*)=0");

		private String sql;

		Query(String sql) {
			this.sql = sql;
		}

		public String getSql() {
			return this.sql;
		}
	}

	/**
	 * Instantiate a {@link JdbcMetadataStore} using provided dataSource {@link DataSource}.
	 * @param dataSource a {@link DataSource}
	 */
	public JdbcMetadataStore(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	/**
	 * Instantiate a {@link JdbcMetadataStore} using provided jdbcOperations {@link JdbcOperations}.
	 * @param jdbcOperations a {@link JdbcOperations}
	 */
	public JdbcMetadataStore(JdbcOperations jdbcOperations) {
		Assert.notNull(jdbcOperations, "'jdbcOperations' must not be null");
		this.jdbcTemplate = jdbcOperations;
	}

	/**
	 * Public setter for the table prefix property. This will be prefixed to all the table names before queries are
	 * executed. Defaults to {@link #DEFAULT_TABLE_PREFIX}.
	 *
	 * @param tablePrefix the tablePrefix to set
	 */
	public void setTablePrefix(String tablePrefix) {
		this.tablePrefix = tablePrefix;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public String putIfAbsent(String key, String value) {
		Assert.notNull(key, "'key' cannot be null");
		Assert.notNull(value, "'value' cannot be null");
		while (true) {
			//try to insert if does not exists
			int affectedRows = this.jdbcTemplate.update(getQuery(Query.PUT_IF_ABSENT_VALUE), ps -> {
				ps.setString(1, key);
				ps.setString(2, value);
				ps.setString(3, key);
			});
			if (affectedRows > 0) {
				//it was not in the table, so we have just inserted it
				return null;
			}
			else {
				//value should be in table. try to return it
				try {
					return this.jdbcTemplate.queryForObject(getQuery(Query.GET_VALUE), String.class, key);
				}
				catch (EmptyResultDataAccessException e) {
					//somebody deleted it between calls. try to insert again (go to beginning of while loop)
				}
			}
		}
	}

	@Override
	public boolean replace(String key, String oldValue, String newValue) {
		Assert.notNull(key, "'key' cannot be null");
		Assert.notNull(oldValue, "'oldValue' cannot be null");
		Assert.notNull(newValue, "'newValue' cannot be null");
		int affectedRows = this.jdbcTemplate.update(getQuery(Query.REPLACE_VALUE), ps -> {
			ps.setString(1, newValue);
			ps.setString(2, key);
			ps.setString(3, oldValue);
		});
		return affectedRows > 0;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void put(String key, String value) {
		Assert.notNull(key, "'key' cannot be null");
		Assert.notNull(value, "'value' cannot be null");
		while (true) {
			//try to insert if does not exist, if exists we will try to update it
			int affectedRows = this.jdbcTemplate.update(getQuery(Query.PUT_IF_ABSENT_VALUE), ps -> {
				ps.setString(1, key);
				ps.setString(2, value);
				ps.setString(3, key);
			});
			if (affectedRows == 0) {
				//since value is not inserted, means it is already present
				try {
					//lock row for updating
					this.jdbcTemplate.queryForObject(getQuery(Query.GET_VALUE_FOR_UPDATE), String.class, key);
				}
				catch (EmptyResultDataAccessException e) {
					//if there are no rows with this key, somebody deleted it in between two calls
					continue;	//try to insert again from beginning
				}
				//lock successful, so - replace
				this.jdbcTemplate.update(getQuery(Query.REPLACE_VALUE_BY_KEY), ps -> {
					ps.setString(1, value);
					ps.setString(2, key);
				});
			}
			return;
		}
	}

	@Override
	public String get(String key) {
		Assert.notNull(key, "'key' cannot be null");
		try {
			return this.jdbcTemplate.queryForObject(getQuery(Query.GET_VALUE), String.class, key);
		}
		catch (EmptyResultDataAccessException e) {
			//if there are no rows with this key, return null
			return null;
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public String remove(String key) {
		Assert.notNull(key, "'key' cannot be null");
		String oldValue;
		try {
			//select old value and lock row for removal
			oldValue = this.jdbcTemplate.queryForObject(getQuery(Query.GET_VALUE_FOR_UPDATE), String.class, key);
		}
		catch (EmptyResultDataAccessException e) {
			//key is not present, so no need to delete it
			return null;
		}
		//delete row and return old value
		int updated = this.jdbcTemplate.update(getQuery(Query.REMOVE_VALUE), key);
		if (updated != 0) {
			return oldValue;
		}
		return null;
	}

	private String getQuery(Query base) {
		return this.queryCache.computeIfAbsent(base, b ->
				StringUtils.replace(b.getSql(), "%PREFIX%", this.tablePrefix));
	}
}

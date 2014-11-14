package dbcache.conf;

import java.io.Serializable;

/**
 * 缓存规则合集
 * @author Jake
 * @date 2014年8月13日上午1:15:55
 */
public class CacheRule {

	/**
	 * 取得实体key
	 * @param id 主键id
	 * @param entityClazz 实体类型
	 * @return String key
	 */
	public static Integer getEntityIdKey(Serializable id, Class<?> entityClazz) {
		return Integer.valueOf((17 + entityClazz.hashCode()) * 37 + id.hashCode());
	}


	/**
	 * 获取索引Key
	 * @param indexName 索引名
	 * @param indexValue 索引值
	 * @return
	 */
	public static Integer getIndexIdKey(String indexName, Object indexValue) {
		return Integer.valueOf((17 + indexName.hashCode()) * 37 + indexValue.hashCode());
	}

}

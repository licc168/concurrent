package dbcache.service.impl;

import static dbcache.conf.CfgConstants.KEY_DB_POOL_CAPACITY;
import static dbcache.conf.CfgConstants.KEY_SERVER_ID_SET;
import static dbcache.conf.CfgConstants.MAX_QUEUE_SIZE_BEFORE_PERSIST;
import static dbcache.conf.CfgConstants.DELAY_WAITTIMMER;
import static dbcache.conf.CfgConstants.SPLIT;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.PostConstruct;
import javax.persistence.Entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import dbcache.key.IdGenerator;
import dbcache.key.LongGenerator;
import dbcache.key.ServerEntityIdRule;
import dbcache.key.annotation.Id;
import dbcache.key.annotation.IdGenerate;
import dbcache.service.DbAccessService;
import dbcache.service.DbRuleService;
import dbcache.utils.GenericsUtils;
import dbcache.utils.PackageScanner;
import dbcache.utils.ReflectionUtility;

/**
 * 数据库规则服务接口实现类
 * 优先使用properties作为配置的值,其次使用spring配置文件,然后使用默认值
 * @author jake
 * @date 2014-8-1-下午9:39:17
 */
@Component
public class DbRuleServiceImpl implements DbRuleService {

	/**
	 * logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(DbRuleServiceImpl.class);


	@Autowired
	private ApplicationContext applicationContext;


	@Autowired
	@Qualifier("jdbcDbAccessServiceImpl")
	private DbAccessService dbAccessService;


	/**
	 * 实体主键ID生成map {服ID ： {实体类： 主键id生成器} }
	 */
	private final ConcurrentMap<Integer, ConcurrentMap<Class<?>, IdGenerator<?>>>  SERVER_ENTITY_IDGENERATOR_MAP = new ConcurrentHashMap<Integer, ConcurrentMap<Class<?>, IdGenerator<?>>> ();


	/**
	 * 入库线程池大小
	 */
	@Autowired(required = false)
	@Qualifier("dbPoolSize")
	private int dbPoolSize;

	/**
	 * 实体缓存数量限制
	 */
	@Autowired(required = false)
	@Qualifier("entityCacheSize")
	private int entityCacheSize;

	/**
	 * 延迟入库时间
	 */
	@Autowired(required = false)
	@Qualifier("delayWaitTimmer")
	private long delayWaitTimmer;

	/**
	 * 实体扫描包
	 */
	@Autowired(required = false)
	@Qualifier("entityPackages")
	private String entityPackages = "dbcache";

	/**
	 * 配置文件位置
	 */
	@Autowired(required = false)
	@Qualifier("dbCachedCfgLocation")
	private String location = "classpath:ServerCfg.properties";

	/**
	 * 配置文件属性
	 */
	private Properties properties;

	/**
	 * 服标识ID列表
	 */
	private List<Integer> serverIdList;

	/**
	 * 默认延迟入库时间
	 */
	private static final long DEFAULT_DELAY_WAITTIMMER = 10000;

	/**
	 * 缺省实体缓存最大容量
	 */
	private static final int DEFAULT_MAX_CAPACITY_OF_ENTITY_CACHE = 1000000;

	/**
	 * 服标识部分ID基础值
	 */
	private final int ID_BASE_VALUE_OF_SERVER = 10000;

	/**
	 * 服标识部分ID最大值
	 */
	private final int ID_MAX_VALUE_OF_SERVER = 99999;

	/**
	 * 服标识最大值
	 */
	private final int SERVER_ID_MAX_VALUE = ID_MAX_VALUE_OF_SERVER - ID_BASE_VALUE_OF_SERVER;

	/**
	 * 服标识最小值
	 */
	private final int SERVER_ID_MIN_VALUE = 0;

	/**
	 * 自增部分的ID最大值(10个9)
	 */
	private final long ID_MAX_VALUE_OF_AUTOINCR = 9999999999L;

	/**
	 * 自增部分的ID最小值
	 */
	private final long ID_MIN_VALUE_OF_AUTOINCR = 0L;

	/**
	 * 自增部分的ID最小值补齐字符串
	 */
	public final String STR_VALUE_OF_AUTOINCR_ID_MIN_VALUE = String.format("%010d", ID_MIN_VALUE_OF_AUTOINCR);

	/**
	 * 玩家id长度
	 */
	private final int MAX_LENGTH_OF_USER_ID = 15;


	@PostConstruct
	public void init() {

		// 加载配置文件
		Resource resource = this.applicationContext.getResource(location);
		properties = new Properties();
		try {
			properties.load(resource.getInputStream());
		} catch (IOException e) {
			FormattingTuple message = MessageFormatter.format("DbCached 资源[{}]加载失败!", location);
			logger.error(message.getMessage(), e);
			throw new RuntimeException(message.getMessage(), e);
		}

		//初始化服ID列表
		if (properties.containsKey(KEY_SERVER_ID_SET)) {
			String serverIdSet = properties.getProperty(KEY_SERVER_ID_SET);
			if (serverIdSet != null && serverIdSet.trim().length() > 0) {
				String[] serverIdArray = serverIdSet.trim().split(SPLIT);
				if (serverIdArray != null && serverIdArray.length > 0) {
					this.serverIdList = new ArrayList<Integer>(serverIdArray.length);

					try {
						for (String sid: serverIdArray) {
							int serverId = Integer.parseInt(sid.trim());
							//非法的服标识
							if (!ServerEntityIdRule.isLegalServerId(serverId)) {
								this.serverIdList = null;
								logger.error("服务器ID标识超出范围：{}", serverId);

								break;
							}

							if (!serverIdList.contains(serverId)) {
								serverIdList.add(serverId);
							}
						}

					} catch (Exception ex) {
						this.serverIdList = null;
						logger.error("DbCached 转换服务器ID标识集合错误： {}", ex.getMessage());
					}
				}
			}
		}

		if (this.serverIdList == null || this.serverIdList.size() < 1) {
			FormattingTuple message = MessageFormatter.format("DbCached [{}] 配置项 '服务器ID标识集合[{}]' 配置错误!", location, KEY_SERVER_ID_SET);
			logger.error(message.getMessage());
			throw new IllegalArgumentException(message.getMessage());
		}


		//入库线程池容量
		int dbPoolSize = Runtime.getRuntime().availableProcessors();
		try {
			dbPoolSize = Integer.parseInt(properties.getProperty(KEY_DB_POOL_CAPACITY));
		} catch (Exception ex) {
			logger.error("转换'{}'失败， 使用缺省值", KEY_DB_POOL_CAPACITY);
		}
		this.dbPoolSize = this.dbPoolSize > 0 ? this.dbPoolSize : dbPoolSize;


		//实体缓存最大容量
		int entityCacheSize = DEFAULT_MAX_CAPACITY_OF_ENTITY_CACHE;
		try {
			entityCacheSize = Integer.parseInt(properties.getProperty(MAX_QUEUE_SIZE_BEFORE_PERSIST));
		} catch (Exception ex) {
			logger.error("转换'{}'失败， 使用缺省值", MAX_QUEUE_SIZE_BEFORE_PERSIST);
		}
		this.entityCacheSize = this.entityCacheSize > 0? this.entityCacheSize : entityCacheSize;

		//实体缓存最大容量
		long delayWaitTimmer = DEFAULT_DELAY_WAITTIMMER;
		try {
			delayWaitTimmer = Integer.parseInt(properties.getProperty(DELAY_WAITTIMMER));
		} catch (Exception ex) {
			logger.error("转换'{}'失败， 使用缺省值", MAX_QUEUE_SIZE_BEFORE_PERSIST);
		}
		this.delayWaitTimmer = this.delayWaitTimmer > 0? this.delayWaitTimmer : delayWaitTimmer;


		//初始化主键Id生成器
		if (this.serverIdList == null || this.serverIdList.size() == 0) {
			return;
		}

		Collection<Class<?>> clzList = PackageScanner.scanPackages(entityPackages);
		if (clzList == null || clzList.size() <= 0) {
			return;
		}

		for (Class<?> clz: clzList) {
			//非实体
			if (!clz.isAnnotationPresent(IdGenerate.class) && !clz.isAnnotationPresent(Entity.class)) {
				continue;
			}

			//获取可用主键类型
			Class<?> idType = GenericsUtils.getSuperClassGenricType(clz, 0);
			if (idType == null || idType != Long.class) {

				@SuppressWarnings("unchecked")
				Field[] fields = ReflectionUtility.getDeclaredFieldsWith(clz, Id.class, javax.persistence.Id.class);

				if(fields != null && fields.length == 1) {
					ReflectionUtils.makeAccessible(fields[0]);
					if (fields[0].getType() != Long.class) {
						continue;
					}
				} else {
					continue;
				}
			}

			//配置的服
			for (int serverId: serverIdList) {

				ConcurrentMap<Class<?>, IdGenerator<?>> classIdGeneratorMap = this.getClassIdGeneratorMap(serverId);

				//已经注册了主键id生成器
				if (classIdGeneratorMap.containsKey(clz)) {
					continue;
				}

				long minValue = ServerEntityIdRule.getMinValueOfEntityId(serverId);
				long maxValue = ServerEntityIdRule.getMaxValueOfEntityId(serverId);

				//当前最大id
				long currMaxId = minValue;
				Object resultId = dbAccessService.loadMaxId(clz, minValue, maxValue);
				if (resultId != null) {
					currMaxId = (Long) resultId;
				}

				LongGenerator idGenerator = new LongGenerator(currMaxId);
				classIdGeneratorMap.putIfAbsent(clz, idGenerator);

				if (logger.isInfoEnabled()) {
					logger.info("服{}： {} 的当前自动增值ID：{}", new Object[] {serverId, clz.getName(), currMaxId});
				}
			}
		}

	}


	@Override
	public Object getIdAutoGenerateValue(Class<?> clazz) {
		int serverId = this.getFirstServerId();
		return this.getIdAutoGenerateValue(serverId, clazz);
	}


	@Override
	public Object getIdAutoGenerateValue(int serverId, Class<?> clazz) {
		if (!containsServerId(serverId)) {
			return null;
		}

		ConcurrentMap<Class<?>, IdGenerator<?>> classIdGeneratorMap = getClassIdGeneratorMap(serverId);
		IdGenerator<?> idGenerator = classIdGeneratorMap.get(clazz);
		if (idGenerator != null) {
			return idGenerator.generateId();
		}

		return null;
	}


	/**
	 * 取得服所对应的实体主键id生成器Map(不存在就创建)
	 * @param serverId 服标识
	 * @return ConcurrentMap<Class<?>, IdGenerator<?>>
	 */
	private ConcurrentMap<Class<?>, IdGenerator<?>> getClassIdGeneratorMap(int serverId) {
		ConcurrentMap<Class<?>, IdGenerator<?>> classIdGeneratorMap = SERVER_ENTITY_IDGENERATOR_MAP.get(serverId);
		if (classIdGeneratorMap == null) {
			classIdGeneratorMap = new ConcurrentHashMap<Class<?>, IdGenerator<?>>();
			SERVER_ENTITY_IDGENERATOR_MAP.putIfAbsent(serverId, classIdGeneratorMap);
		}

		return SERVER_ENTITY_IDGENERATOR_MAP.get(serverId);
	}


	@Override
	public void registerEntityIdGenerator(int serverId, Class<?> clazz, IdGenerator<?> idGenerator) {
		ConcurrentMap<Class<?>, IdGenerator<?>> classIdGeneratorMap = getClassIdGeneratorMap(serverId);

		if (idGenerator == null) {
			classIdGeneratorMap.remove(clazz);
		} else {
			classIdGeneratorMap.put(clazz, idGenerator);
		}
	}


	/**
	 * 判断是否是合法的服标识
	 * @param serverId 服标识id
	 * @return true/false
	 */
	public boolean isLegalServerId(int serverId) {
		return serverId >= SERVER_ID_MIN_VALUE && serverId <= SERVER_ID_MAX_VALUE;
	}

	/**
	 * 取得实体ID最大值
	 * @param serverId 服标识id
	 * @return long
	 */
	public long getMaxValueOfEntityId(int serverId) {
		int valueOfServer = ID_BASE_VALUE_OF_SERVER + serverId;
		String valueStr = new StringBuffer().append(valueOfServer)
											.append(ID_MAX_VALUE_OF_AUTOINCR)
											.toString();
		return Long.valueOf(valueStr);
	}

	/**
	 * 取得实体ID最小值
	 * @param serverId  服标识id
	 * @return long
	 */
	public long getMinValueOfEntityId(int serverId) {
		int valueOfServer = ID_BASE_VALUE_OF_SERVER + serverId;
		String valueStr = new StringBuffer().append(valueOfServer)
											.append(STR_VALUE_OF_AUTOINCR_ID_MIN_VALUE)
											.toString();
		return Long.valueOf(valueStr);
	}

	/**
	 * 从玩家id中取得服标识ID
	 * @param userId 玩家id
	 * @return int
	 */
	public int getServerIdFromUser(long userId) {
		String userIdString = String.valueOf(userId);
		if (userIdString.length() == MAX_LENGTH_OF_USER_ID) {
			return Integer.parseInt(userIdString.substring(0, 5)) - ID_BASE_VALUE_OF_SERVER;
		}

		return -1;
	}

	/**
	 * 从玩家id中取得自增部分
	 * @param userId 玩家id
	 * @return int
	 */
	public long getAutoIncrPartFromUser(long userId) {
		int serverId = getServerIdFromUser(userId);
		if (serverId >= 0) {
			long minValue = getMinValueOfEntityId(serverId);
			return userId - minValue;
		}
		return -1L;
	}


	@Override
	public List<Integer> getServerIdList() {
		if (this.serverIdList == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(serverIdList);
	}


	/**
	 * 获取第一个服Id
	 * @return
	 */
	private int getFirstServerId() {
		if (this.serverIdList == null || this.serverIdList.size() == 0) {
			return 0;
		}
		return this.serverIdList.get(0);
	}


	@Override
	public boolean isServerMerged() {
		return this.serverIdList != null && this.serverIdList.size() > 1 ? true : false;
	}

	@Override
	public boolean containsServerId(int serverId) {
		return this.serverIdList != null && this.serverIdList.contains(serverId);
	}

	@Override
	public int getDbPoolSize() {
		return dbPoolSize;
	}

	@Override
	public String getEntityPackages() {
		return entityPackages;
	}

	@Override
	public int getEntityCacheSize() {
		return entityCacheSize;
	}

	@Override
	public long getDelayWaitTimmer() {
		return delayWaitTimmer;
	}

}

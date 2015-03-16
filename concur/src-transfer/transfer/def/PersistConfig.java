package transfer.def;

import org.apache.mina.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ReflectionUtils;

import transfer.ByteArray;
import transfer.anno.Ignore;
import transfer.anno.Transferable;
import transfer.core.ClassInfo;
import transfer.core.EnumInfo;
import transfer.core.FieldInfo;
import transfer.deserializer.*;
import transfer.exception.UnsupportClassException;
import transfer.exception.UnsupportDeserializerTypeException;
import transfer.exception.UnsupportSerializerTypeException;
import transfer.serializer.*;
import transfer.utils.ByteMap;
import transfer.utils.IdentityHashMap;
import transfer.utils.IntegerMap;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persist Config配置选项
 * Created by Jake on 2015/2/23.
 */
public class PersistConfig {

    private static final Logger logger = LoggerFactory.getLogger(TransferConfig.class);

    public static final NullDeserializer NULL_DESERIALIZER = NullDeserializer.getInstance();

    static final ByteMap<Deserializer> deserializers = new ByteMap<Deserializer>();

    static final IdentityHashMap<Type, Deserializer> typedDeserializers = new IdentityHashMap<Type, Deserializer>();

    static final IdentityHashMap<Class, Serializer> serializers = new IdentityHashMap<Class, Serializer>();

    static final IdentityHashMap<Class, ClassInfo> classInfoMap = new IdentityHashMap<Class, ClassInfo>();

    static final IntegerMap<Class> classIdMap = new IntegerMap<Class>();

    static final IdentityHashMap<Class, Integer> idClassMap = new IdentityHashMap<Class, Integer>();


    // 1111 0000 类型
    public static final byte TYPE_MASK = (byte) 0xF0;

    // 0000 1111 额外信息
    public static final byte EXTRA_MASK = (byte) 0x0F;


    // Number类型标记
    public static final byte INT1 = 0x00;// int 1字节
    public static final byte INT2 = 0x01;// int 2字节
    public static final byte INT3 = 0x02;// int 3字节
    public static final byte INT4 = 0x03;// int 4字节
    public static final byte INT5 = 0x04;// long 5字节
    public static final byte INT6 = 0x05;// long 6字节
    public static final byte INT7 = 0x06;// long 7字节
    public static final byte INT8 = 0x07;// long 8字节

    // Decimal类型标记
    public static final byte FLOAT = 0x00;// float 4字节
    public static final byte DOUBLE = 0x01;// double 8字节


    /**
     * 注册类
     * @param clazz 传输类
     * @param id 唯一编号
     */
    public static void registerClass(Class<?> clazz, int id) {

        Class oldClass = classIdMap.get(id);
        if (oldClass != null) {
            logger.warn("注册解析类Id重复: " + clazz + ",Id: " + id + " , (" + oldClass + ")");
        }

        classIdMap.put(id, clazz);
        idClassMap.put(clazz, id);

        boolean repeatRegisterSerializers,repeatRegisterDeSerializers;
        if (clazz.isEnum() || (clazz.getSuperclass() != null && clazz.getSuperclass().isEnum())) { // 枚举类型
            repeatRegisterSerializers = serializers.put(clazz, TagEnumSerializer.getInstance());
            repeatRegisterDeSerializers = typedDeserializers.put(clazz, TagEnumDeserializer.getInstance());
        } else {
            repeatRegisterSerializers = serializers.put(clazz, TagObjectSerializer.getInstance());
            repeatRegisterDeSerializers = typedDeserializers.put(clazz, TagObjectDeSerializer.getInstance());
        }

        if (repeatRegisterSerializers || repeatRegisterDeSerializers) {
            logger.warn("重复注册解析类:" + clazz + ",Id:" + id);
        }
    }


    /**
     * 注册自定义解析器
     * @param clazz 类
     * @param flag 标记
     * @param deserializer 解析器
     */
    public static void registerDeserializer(Class<?> clazz, byte flag, Deserializer deserializer) {
        deserializers.put(flag, deserializer);
        typedDeserializers.put(clazz, deserializer);
    }


    /**
     * 注册自定义编码器
     * @param clazz 类
     * @param serializer 编码器
     */
    public static void registerSerializer(Class<?> clazz, Serializer serializer) {
        serializers.put(clazz, serializer);
    }


    /**
     * 获取解析器
     * @param type 类型
     * @see transfer.def.Types
     * @return
     */
    public static Deserializer getDeserializer(byte type) {
        Deserializer deserializer = deserializers.get(type);
        if (deserializer == null) {
            throw new UnsupportDeserializerTypeException(type);
        }
        return deserializer;
    }


    /**
     * 获取解析器
     * @param type 类型
     * @see transfer.def.Types
     * @return
     */
    public static Deserializer getDeserializer(Type type) {

        if (type == null || type == Object.class) {
            return null;
        }

        if (type instanceof Class<?>) {

            return getDeserializer((Class<?>) type, type);
        }

        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return getDeserializer((Class<?>) rawType, type);
            } else {
                return getDeserializer(rawType);
            }
        }

        throw new UnsupportDeserializerTypeException(type);
    }


    /**
     * 获取解析器
     * @param type 类型
     * @see transfer.def.Types
     * @return
     */
    public static Deserializer getDeserializer(Type type, byte flag) {

        if (flag == Types.NULL) {
            return NULL_DESERIALIZER;
        }

        if (type == null || type == Object.class) {
            return deserializers.get(TransferConfig.getType(flag));
        }

        Deserializer deserializer = typedDeserializers.get(type);
        if (deserializer != null) {
            return deserializer;
        }

        if (type instanceof Class<?>) {

            return getDeserializer((Class<?>) type, type);
        }

        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return getDeserializer((Class<?>) rawType, type);
            } else {
                return getDeserializer(rawType, flag);
            }
        }

        throw new UnsupportDeserializerTypeException(type);
    }


    private static Deserializer getDeserializer(Class<?> clazz, Type type) {

        Deserializer deserializer = typedDeserializers.get(type);
        if (deserializer != null) {
            return deserializer;
        }

        if (type == null) {
            type = clazz;
        }

        deserializer = typedDeserializers.get(type);
        if (deserializer != null) {
            return deserializer;
        }

        if (type instanceof WildcardType || type instanceof TypeVariable || type instanceof ParameterizedType) {
            deserializer = typedDeserializers.get(clazz);
        }

        if (deserializer != null) {
            typedDeserializers.put(type, deserializer);
            return deserializer;
        }


        if (clazz.isArray()) {
            deserializer =  ArrayDeSerializer.getInstance();
        } else if(clazz.isEnum()) {
            deserializer =  TagEnumDeserializer.getInstance();
        } else if (clazz == Set.class || clazz == HashSet.class || clazz == Collection.class || clazz == List.class
                    || clazz == ArrayList.class) {
            deserializer =  CollectionDeSerializer.getInstance();
        } else if(Collection.class.isAssignableFrom(clazz)) {
            deserializer =  CollectionDeSerializer.getInstance();
        } else if (Map.class.isAssignableFrom(clazz)) {
            deserializer =  MapDeSerializer.getInstance();
        } else if(byte[].class == clazz) {
            deserializer =  ByteArrayDeSerializer.getInstance();
        } else if (Map.Entry.class.isAssignableFrom(clazz)) {
            deserializer =  EntryDeserializer.getInstance();
        } else {
            deserializer = TagObjectDeSerializer.getInstance();

            // 注册类型
            autoRegisterClass(clazz);
        }


        if (deserializer == null) {
            throw new UnsupportDeserializerTypeException(type);
        }

        typedDeserializers.put(type, deserializer);

        return deserializer;
    }


    // 自动注册类型
    private static void autoRegisterClass(Class<?> clazz) {
        // 获取唯一标识
        if (clazz.isAnnotationPresent(Transferable.class)) {
            Transferable transferable = clazz.getAnnotation(Transferable.class);
            // 注册为对象解析方式
            registerClass(clazz, transferable.id());

            return;
        }

        throw new UnsupportDeserializerTypeException(clazz);
    }


    /**
     * 获取编码器
     * @param clazz 类型
     * @return
     */
    public static Serializer getSerializer(Class clazz) {

        Serializer serializer = serializers.get(clazz);
        if (serializer != null) {
            return serializer;
        }


        if (Map.class.isAssignableFrom(clazz)) {
            serializers.put(clazz, MapSerializer.getInstance());
        } else if (List.class.isAssignableFrom(clazz)) {
            serializers.put(clazz, CollectionSerializer.getInstance());
        } else if (Collection.class.isAssignableFrom(clazz)) {
            serializers.put(clazz, CollectionSerializer.getInstance());
        } else if (Date.class.isAssignableFrom(clazz)) {
            serializers.put(clazz, DateSerializer.getInstance());
        } else if (clazz.isEnum() || (clazz.getSuperclass() != null && clazz.getSuperclass().isEnum())) {
            // 注册类型
            autoRegisterClass(clazz);
        } else if (clazz.isArray()) {
            serializers.put(clazz, ArraySerializer.getInstance());
        } else {
            boolean isCglibProxy = false;
            boolean isJavassistProxy = false;
            boolean isAsmProxy = false;
            for (Class<?> item : clazz.getInterfaces()) {
                if (item.getName().equals("net.sf.cglib.proxy.Factory")
                        || item.getName().equals("org.springframework.cglib.proxy.Factory")) {
                    isCglibProxy = true;
                    break;
                } else if (item.getName().equals("javassist.util.proxy.ProxyObject")) {
                    isJavassistProxy = true;
                    break;
                } else if (item.getName().equals("dbcache.EnhancedEntity")) {//REMEMBER
                    isAsmProxy = true;
                    break;
                }
            }

            if (isCglibProxy || isJavassistProxy || isAsmProxy) {
                ObjectAsmProxySerializer superWriter = ObjectAsmProxySerializer.getInstance();
                serializers.put(clazz, superWriter);
                return superWriter;
            }

            if (Proxy.isProxyClass(clazz)) {
                serializers.put(clazz, TagObjectSerializer.getInstance());// TODO
            } else {
                // 注册类型
                autoRegisterClass(clazz);
            }
        }

        serializer = serializers.get(clazz);

        if (serializer == null) {
            throw new UnsupportSerializerTypeException(clazz);
        }

        return serializer;
    }


    /**
     * 获取类信息
     * @param clazz 类
     * @return
     */
    public static ClassInfo getOrCreateClassInfo(final Class clazz) {

        ClassInfo classInfo = classInfoMap.get(clazz);
        if (classInfo != null) {
            return classInfo;
        }

        int classId = PersistConfig.getClassId(clazz);
        // 枚举类型
        if (clazz.isEnum()) {

            classInfo = EnumInfo.valueOf(clazz, classId);
            classInfoMap.put(clazz, classInfo);

        } else {

            final List<FieldInfo> fieldInfos = new ArrayList<FieldInfo>();
            final Map<String, FieldInfo> fieldInfoMap = new HashMap<String, FieldInfo>();

            // 遍历属性信息
            ReflectionUtils.doWithFields(clazz, new ReflectionUtils.FieldCallback() {

                @SuppressWarnings("unchecked")
                public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {

                    // 忽略静态属性和临时属性
                    if (Modifier.isTransient(field.getModifiers()) || Modifier.isStatic(field.getModifiers()) ||
                            field.isAnnotationPresent(javax.persistence.Transient.class)) {
                        return;
                    }

                    // 注解忽略的属性
                    if (field.isAnnotationPresent(Ignore.class)) {
                        return;
                    }

                    try {

                        FieldInfo fieldInfo = FieldInfo.valueOf(clazz, field);

                        fieldInfos.add(fieldInfo);
                        fieldInfoMap.put(field.getName(), fieldInfo);

                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new IllegalAccessException("无法创建属性信息" + clazz.getName() + "#" + field.getName());
                    }

                }
            });

            classInfo = ClassInfo.valueOf(clazz, classId, fieldInfos, fieldInfoMap);

            classInfoMap.put(clazz, classInfo);
        }

        return classInfo;
    }


    /**
     * 获取类型
     * @param byteMeta
     * @return
     */
    public static byte getType(byte byteMeta) {
        return (byte) (byteMeta & TYPE_MASK);
    }


    /**
     * 获取其他信息
     * @param byteMeta
     * @return
     */
    public static byte getExtra(byte byteMeta) {
        return (byte) (byteMeta & EXTRA_MASK);
    }


    /**
     * 根据Id获取注册类
     * @param id
     * @return
     */
    public static Class<?> getClass(int id) {
        Class<?> result = classIdMap.get(id);

        if (result == null) {
            throw new UnsupportClassException(id);
        }

        return result;
    }


    /**
     * 获取注册类Id
     * @param clazz
     * @return
     */
    public static int getClassId(Class<?> clazz) {
        Integer classId = idClassMap.get(clazz);

        if (classId == null) {
            throw new UnsupportClassException(clazz);
        }

        return classId.intValue();
    }


    static {

        deserializers.put(Types.OBJECT, TagObjectDeSerializer.getInstance());
        deserializers.put(Types.ARRAY, ArrayDeSerializer.getInstance());
        deserializers.put(Types.COLLECTION, CollectionDeSerializer.getInstance());
        deserializers.put(Types.BYTE_ARRAY, ByteArrayDeSerializer.getInstance());
        deserializers.put(Types.MAP, MapDeSerializer.getInstance());
        deserializers.put(Types.NULL, NullDeserializer.getInstance());
        deserializers.put(Types.NUMBER, NumberDeserializer.getInstance());
        deserializers.put(Types.DECIMAL, DecimalDeserializer.getInstance());
        deserializers.put(Types.STRING, StringDeserializer.getInstance());
        deserializers.put(Types.BOOLEAN, BooleanDeserializer.getInstance());
        deserializers.put(Types.ENUM, TagEnumDeserializer.getInstance());
        deserializers.put(Types.DATE_TIME, DateDeserializer.getInstance());


        typedDeserializers.put(Object.class, TagObjectDeSerializer.getInstance());
        typedDeserializers.put(Array.class, ArrayDeSerializer.getInstance());
        typedDeserializers.put(Collection.class, CollectionDeSerializer.getInstance());
        typedDeserializers.put(List.class, CollectionDeSerializer.getInstance());
        typedDeserializers.put(CopyOnWriteArrayList.class, CollectionDeSerializer.getInstance());
        typedDeserializers.put(ConcurrentLinkedQueue.class, CollectionDeSerializer.getInstance());
        typedDeserializers.put(ConcurrentHashSet.class, CollectionDeSerializer.getInstance());
        typedDeserializers.put(ArrayList.class, CollectionDeSerializer.getInstance());
        typedDeserializers.put(ConcurrentSkipListSet.class, CollectionDeSerializer.getInstance());
        typedDeserializers.put(byte[].class, ByteArrayDeSerializer.getInstance());
        typedDeserializers.put(Byte[].class, ByteArrayDeSerializer.getInstance());
        typedDeserializers.put(Map.class, MapDeSerializer.getInstance());
        typedDeserializers.put(HashMap.class, MapDeSerializer.getInstance());
        typedDeserializers.put(ConcurrentHashMap.class, MapDeSerializer.getInstance());
        typedDeserializers.put(Map.Entry.class, EntryDeserializer.getInstance());
        typedDeserializers.put(null, NullDeserializer.getInstance());
        typedDeserializers.put(Number.class, NumberDeserializer.getInstance());
        typedDeserializers.put(short.class, NumberDeserializer.getInstance());
        typedDeserializers.put(Short.class, NumberDeserializer.getInstance());
        typedDeserializers.put(int.class, NumberDeserializer.getInstance());
        typedDeserializers.put(Integer.class, NumberDeserializer.getInstance());
        typedDeserializers.put(long.class, NumberDeserializer.getInstance());
        typedDeserializers.put(Long.class, NumberDeserializer.getInstance());
        typedDeserializers.put(BigInteger.class, NumberDeserializer.getInstance());
        typedDeserializers.put(BigDecimal.class, NumberDeserializer.getInstance());
        typedDeserializers.put(float.class, DecimalDeserializer.getInstance());
        typedDeserializers.put(Float.class, DecimalDeserializer.getInstance());
        typedDeserializers.put(double.class, DecimalDeserializer.getInstance());
        typedDeserializers.put(Double.class, DecimalDeserializer.getInstance());
        typedDeserializers.put(AtomicInteger.class, NumberDeserializer.getInstance());
        typedDeserializers.put(AtomicLong.class, NumberDeserializer.getInstance());
        typedDeserializers.put(String.class, StringDeserializer.getInstance());
        typedDeserializers.put(Boolean.class, BooleanDeserializer.getInstance());
        typedDeserializers.put(Enum.class, TagEnumDeserializer.getInstance());
        typedDeserializers.put(Date.class, DateDeserializer.getInstance());
        typedDeserializers.put(ByteArray.class, ByteArrayWrappDeSerializer.getInstance());


        serializers.put(Object.class, TagObjectSerializer.getInstance());
        serializers.put(Collection.class, CollectionSerializer.getInstance());
        serializers.put(List.class, CollectionSerializer.getInstance());
        serializers.put(ConcurrentHashSet.class, CollectionSerializer.getInstance());
        serializers.put(ArrayList.class, CollectionSerializer.getInstance());
        serializers.put(CopyOnWriteArrayList.class, CollectionSerializer.getInstance());
        serializers.put(ConcurrentSkipListSet.class, CollectionSerializer.getInstance());
        serializers.put(Map.class, MapSerializer.getInstance());
        serializers.put(HashMap.class, MapSerializer.getInstance());
        serializers.put(ConcurrentHashMap.class, MapSerializer.getInstance());
        serializers.put(ConcurrentSkipListMap.class, MapSerializer.getInstance());
        serializers.put(Boolean.class, BooleanSerializer.getInstance());
        serializers.put(boolean.class, BooleanSerializer.getInstance());
        serializers.put(byte[].class, ByteArraySerializer.getInstance());
        serializers.put(Byte[].class, ByteArraySerializer.getInstance());
        serializers.put(Number.class, NumberSerializer.getInstance());
        serializers.put(short.class, NumberSerializer.getInstance());
        serializers.put(Short.class, NumberSerializer.getInstance());
        serializers.put(int.class, NumberSerializer.getInstance());
        serializers.put(Integer.class, NumberSerializer.getInstance());
        serializers.put(long.class, NumberSerializer.getInstance());
        serializers.put(Long.class, NumberSerializer.getInstance());
        serializers.put(BigInteger.class, NumberSerializer.getInstance());
        serializers.put(BigDecimal.class, NumberSerializer.getInstance());
        serializers.put(float.class, DecimalSerializer.getInstance());
        serializers.put(Float.class, DecimalSerializer.getInstance());
        serializers.put(double.class, DecimalSerializer.getInstance());
        serializers.put(Double.class, DecimalSerializer.getInstance());
        serializers.put(AtomicInteger.class, NumberSerializer.getInstance());
        serializers.put(AtomicLong.class, NumberSerializer.getInstance());
        serializers.put(String.class, StringSerializer.getInstance());
        serializers.put(StringBuffer.class, StringSerializer.getInstance());
        serializers.put(StringBuilder.class, StringSerializer.getInstance());
        serializers.put(CharSequence.class, StringSerializer.getInstance());
        serializers.put(Character.class, StringSerializer.getInstance());


    }


}
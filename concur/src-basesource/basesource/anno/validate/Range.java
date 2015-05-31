package basesource.anno.validate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import basesource.contants.ValueType;
import basesource.validators.ValueGetter;

/**
 * 数字范围
 * @author Jake
 *
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Range {
	
	/**
	 * 最小值
	 * @return
	 */
	public long min();
	
	/**
	 * 最大值
	 * @return
	 */
	public long max();
	
	/**
	 * 错误提示信息
	 * @return
	 */
	public String msg() default "";
	
	/**
	 * 值(json格式的值使用:{value}.xxx.xx)
	 * @return
	 */
	public String[] value() default ValueType.VALUE;
	
	/**
	 * 获取值的类
	 * @return
	 */
	public Class<? extends ValueGetter> valueGetter();
	
}
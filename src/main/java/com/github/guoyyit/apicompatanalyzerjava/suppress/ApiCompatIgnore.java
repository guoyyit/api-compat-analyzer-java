package com.github.guoyyit.apicompatanalyzerjava.suppress;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type or member as exempt from compatibility checks. The analyzer
 * matches this annotation by simple name, so analyzed projects may also
 * declare their own annotation named {@code ApiCompatIgnore}. Alternatively
 * a comment containing {@code api-compat:ignore} on the declaration works.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface ApiCompatIgnore {
    String reason() default "";
}

package com.github.guoyyit.apicompatanalyzerjava.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public API element as exempt from compatibility change reporting.
 *
 * <p>The annotation is recognized by simple name ({@code ApiCompatExempt}) when parsing
 * source snapshots, so analyzed projects do not need this class on their classpath.
 * Exempt results are recorded in the report and can be persisted into a baseline for
 * comparison with later versions.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface ApiCompatExempt {

    /** Human readable reason for the exemption. */
    String reason() default "";

    /**
     * Optional change selector to scope the exemption, for example
     * {@code "REMOVED"} or {@code "SIGNATURE_CHANGED"}. Empty means all changes.
     */
    String change() default "";
}

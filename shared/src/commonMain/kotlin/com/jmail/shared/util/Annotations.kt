package com.jmail.shared.util

/**
 * Marks declarations that carry no logic worth asserting on — platform glue, exhaustive
 * `when` branches over generated types — so they do not dilute the coverage signal.
 *
 * Applying this to anything with a branch in it is a code smell; write the test instead.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class ExcludeFromCoverage(val reason: String = "")

package org.example.constraint;

public enum ValidityStatus {
    /** The constraint is logically true (valid). */
    TRUE,
    /** The constraint is logically false (unsatisfiable). */
    FALSE,
    /** The constraint is satisfiable but not proven true (yet). */
    SATISFIABLE_UNKNOWN,
    /** The status has not been checked yet. */
    NOT_YET_CHECKED
}
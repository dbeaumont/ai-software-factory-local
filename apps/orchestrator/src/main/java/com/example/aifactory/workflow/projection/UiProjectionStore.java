package com.example.aifactory.workflow.projection;

/** Transaction boundary for the rebuildable PostgreSQL UI projection. */
public interface UiProjectionStore {
    /** Replaces one workflow projection in a single transaction after all external facts were verified. */
    void replaceAtomically(UiProjectionSnapshot snapshot);
}

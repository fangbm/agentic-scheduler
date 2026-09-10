package dev.agenticscheduler.domain

/**
 * Marker owned by the pure domain module.
 *
 * D1 keeps this module intentionally small: future Event, Task, Course and
 * Planner contracts belong here, while persistence and UI stay in other modules.
 */
public object DomainModule {
    public const val name: String = "agentic-scheduler-domain"
}

